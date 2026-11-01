package ua.pysanka.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.min
import kotlin.math.roundToInt

/** Pysanka: one recognised word, with where it is on the image. */
data class OcrWord(val text: String, val box: Rect)

/**
 * Pysanka: one recognised piece of a line. Pieces with the same [group] are one line of text
 * (joined with spaces); groups come in reading order.
 */
data class OcrLine(val text: String, val box: Rect, val confidence: Float, val words: List<OcrWord>, val group: Int = 0)

/** Pysanka: the lines joined as text: pieces of one line with spaces, lines with line breaks. */
fun List<OcrLine>.joinedText(): String =
    groupBy { it.group }.values.joinToString("\n") { g -> g.joinToString(" ") { it.text } }

/**
 * Pysanka: text recognition on the phone, with the PP-OCRv5 mobile models built into the app
 * (assets/ocr; where they come from: SOURCES.json, checksums: SHA256SUMS).
 *
 * 1. Detection finds text regions: the picture is scaled so its long side is at most 2048 px (a
 *    multiple of 32; smaller text is lost below that), normalised, and the model gives a
 *    probability map. Pixels above 0.3 are grouped into regions; regions whose mean probability
 *    is below 0.6 are dropped. Each region's tilt comes from its shape (the long axis of its
 *    pixels), so a photo taken at an angle is read line by line; each region is grown back
 *    ("unclipped") by area × 1.5 / perimeter, because the model shrinks text areas.
 * 2. Each region is cut out straightened, scaled to 48 px height and read by the Cyrillic model,
 *    and by the Latin one unless the line has letters only Cyrillic has. The reading with more
 *    confidently read letters wins (not the higher mean: a reading of only the digits of a
 *    Ukrainian line is very sure of them, but misses the words).
 * 3. Reading order: pieces on one line close together form a line; lines that follow each other
 *    and overlap sideways form a block (a column, a card); blocks top to bottom, then left to right.
 * 4. Word positions come from where each character was read (its time step) along the line.
 *
 * Nothing leaves the phone and nothing is stored. The same steps were checked with a Python
 * prototype of this exact logic on the same model files.
 */
class TextRecognizer private constructor(private val context: Context) {
    companion object {
        private const val DIR = "ocr"
        private const val MAX_SIDE = 2048
        private const val THRESHOLD = 0.3f
        private const val BOX_THRESHOLD = 0.6f
        private const val UNCLIP = 1.5f
        private const val MIN_PIXELS = 10
        private const val REC_HEIGHT = 48
        private const val MAX_REC_WIDTH = 3200
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        /** Lines tilted less than this are read straight; more than the max, as found (not text lines). */
        private const val MIN_TILT = 0.035f // about 2°
        private const val MAX_TILT = 0.6f // about 35°
        /** Letters only Cyrillic has (no Latin look-alike): a line with them is not read again as Latin. */
        private const val CYRILLIC_ONLY = "бвгґдєжзийїклмнптфцчшщьюяыэъБГҐДЄЖЗИЙЇЛПФЦЧШЩЬЮЯЫЭЪ"

        @Volatile
        private var instance: TextRecognizer? = null

        fun get(context: Context): TextRecognizer =
            instance ?: synchronized(this) { instance ?: TextRecognizer(context.applicationContext).also { instance = it } }
    }

    private class Reader(val session: OrtSession, val chars: List<String>)

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val mutex = Mutex()
    private var det: OrtSession? = null
    private var readers: List<Reader>? = null

    private fun options() = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(4)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }

    private fun load() {
        if (det != null) return
        val assets = context.assets
        fun bytes(name: String) = assets.open("$DIR/$name").use { it.readBytes() }
        // Built first and assigned only when all three loaded, so a failure leaves nothing half-made
        val d = env.createSession(bytes("det.onnx"), options())
        val r = try {
            listOf("cyrillic", "latin").map { name ->
                val chars = String(bytes("rec_${name}_dict.txt"), Charsets.UTF_8).split('\n').dropLast(1) + " "
                Reader(env.createSession(bytes("rec_$name.onnx"), options()), chars)
            }
        } catch (e: Throwable) {
            d.close()
            throw e
        }
        det = d
        readers = r
    }

    /** Screens using the models now; their memory is freed only when none is. */
    private val holders = java.util.concurrent.atomic.AtomicInteger(0)

    /** A screen starts using the models. */
    fun hold() {
        holders.incrementAndGet()
    }

    /** That screen is gone; frees the models if no other screen uses them. */
    suspend fun letGo() {
        if (holders.updateAndGet { maxOf(0, it - 1) } == 0) release()
    }

    /** Frees the models' memory (about 60 MB) if no screen is using them (after a batch of screenshots). */
    suspend fun releaseIfUnused() {
        if (holders.get() == 0) release()
    }

    private suspend fun release() = mutex.withLock {
        det?.close(); det = null
        readers?.forEach { it.session.close() }; readers = null
    }

    /** All text on [bitmap], in reading order. Runs off the main thread. */
    suspend fun recognize(bitmap: Bitmap): List<OcrLine> = withContext(Dispatchers.Default) {
        mutex.withLock {
            load()
            val read = detect(bitmap).mapNotNull { box -> read(bitmap, box) }.filter { it.text.isNotBlank() }
            order(read)
        }
    }

    // ------------------------------------------------------------------ detection

    /** A text region on the picture: its centre, length along the text, height across, and tilt (radians). */
    private class TextBox(val cx: Float, val cy: Float, val length: Float, val height: Float, val angle: Float) {
        /** The point at [along] (0..1 from the start of the line) and [across] (0..1 from the top). */
        fun point(along: Float, across: Float): Pair<Float, Float> {
            val u = (along - 0.5f) * length
            val v = (across - 0.5f) * height
            val c = cos(angle)
            val s = sin(angle)
            return (cx + u * c - v * s) to (cy + u * s + v * c)
        }

        /** The straight rectangle around the part from [from] to [to] along the line. */
        fun bounds(from: Float = 0f, to: Float = 1f, w: Int, h: Int): Rect {
            val pts = listOf(point(from, 0f), point(to, 0f), point(from, 1f), point(to, 1f))
            return Rect(
                max(0, pts.minOf { it.first }.toInt()), max(0, pts.minOf { it.second }.toInt()),
                min(w, pts.maxOf { it.first }.toInt() + 1), min(h, pts.maxOf { it.second }.toInt() + 1),
            )
        }
    }

    private fun detect(src: Bitmap): List<TextBox> {
        val w = src.width
        val h = src.height
        val scale = min(1f, MAX_SIDE.toFloat() / max(w, h))
        val nw = max(32, (w * scale / 32f).roundToInt() * 32)
        val nh = max(32, (h * scale / 32f).roundToInt() * 32)
        val scaled = Bitmap.createScaledBitmap(src, nw, nh, true)
        val plane = nw * nh
        val prob: FloatArray = run {
            val px = IntArray(plane)
            scaled.getPixels(px, 0, nw, 0, 0, nw, nh)
            if (scaled !== src) scaled.recycle()
            // Channels in BGR order, as the model was trained (Paddle decodes images as BGR)
            val input = FloatBuffer.allocate(3 * plane)
            for (i in 0 until plane) {
                val c = px[i]
                val b = (c and 0xFF) / 255f
                val g = ((c shr 8) and 0xFF) / 255f
                val r = ((c shr 16) and 0xFF) / 255f
                input.put(i, (b - MEAN[0]) / STD[0])
                input.put(plane + i, (g - MEAN[1]) / STD[1])
                input.put(2 * plane + i, (r - MEAN[2]) / STD[2])
            }
            val session = det!!
            OnnxTensor.createTensor(env, input, longArrayOf(1, 3, nh.toLong(), nw.toLong())).use { t ->
                session.run(mapOf(session.inputNames.first() to t)).use { out ->
                    val fb = (out[0] as OnnxTensor).floatBuffer
                    FloatArray(fb.remaining()).also { fb.get(it) }
                }
            }
        }
        val sx = w.toFloat() / nw
        val sy = h.toFloat() / nh
        // Group pixels above the threshold into 4-connected regions
        val labels = IntArray(plane)
        val stack = IntArray(plane)
        val boxes = ArrayList<TextBox>()
        var label = 0
        for (start in 0 until plane) {
            if (prob[start] <= THRESHOLD || labels[start] != 0) continue
            label++
            var top = 0
            stack[top++] = start
            labels[start] = label
            var count = 0
            var sum = 0f
            var x1 = Int.MAX_VALUE; var y1 = Int.MAX_VALUE; var x2 = -1; var y2 = -1
            // Moments, in picture pixels, for the tilt
            var mx = 0.0; var my = 0.0; var mxx = 0.0; var myy = 0.0; var mxy = 0.0
            while (top > 0) {
                val p = stack[--top]
                val x = p % nw
                val y = p / nw
                count++
                sum += prob[p]
                val fx = (x + 0.5) * sx
                val fy = (y + 0.5) * sy
                mx += fx; my += fy; mxx += fx * fx; myy += fy * fy; mxy += fx * fy
                if (x < x1) x1 = x; if (x > x2) x2 = x
                if (y < y1) y1 = y; if (y > y2) y2 = y
                if (x > 0) push(p - 1, prob, labels, label, stack, top)?.let { top = it }
                if (x < nw - 1) push(p + 1, prob, labels, label, stack, top)?.let { top = it }
                if (y > 0) push(p - nw, prob, labels, label, stack, top)?.let { top = it }
                if (y < nh - 1) push(p + nw, prob, labels, label, stack, top)?.let { top = it }
            }
            if (count < MIN_PIXELS || sum / count < BOX_THRESHOLD) continue
            mx /= count; my /= count
            val cxx = mxx / count - mx * mx
            val cyy = myy / count - my * my
            val cxy = mxy / count - mx * my
            var angle = (0.5 * atan2(2 * cxy, cxx - cyy)).toFloat()
            if (abs(angle) < MIN_TILT || abs(angle) > MAX_TILT) angle = 0f
            // Extent along and across the text: a second look at this region's pixels
            val c = cos(angle)
            val s = sin(angle)
            var u0 = Float.MAX_VALUE; var u1 = -Float.MAX_VALUE; var v0 = Float.MAX_VALUE; var v1 = -Float.MAX_VALUE
            for (y in y1..y2) {
                val row = y * nw
                for (x in x1..x2) {
                    if (labels[row + x] != label) continue
                    val dx = ((x + 0.5) * sx - mx).toFloat()
                    val dy = ((y + 0.5) * sy - my).toFloat()
                    val u = dx * c + dy * s
                    val v = -dx * s + dy * c
                    if (u < u0) u0 = u; if (u > u1) u1 = u
                    if (v < v0) v0 = v; if (v > v1) v1 = v
                }
            }
            u0 -= sx / 2; u1 += sx / 2; v0 -= sy / 2; v1 += sy / 2
            val len = u1 - u0
            val ht = v1 - v0
            val d = len * ht * UNCLIP / (2 * (len + ht))
            val uc = (u0 + u1) / 2
            val vc = (v0 + v1) / 2
            val box = TextBox(
                cx = (mx + uc * c - vc * s).toFloat(), cy = (my + uc * s + vc * c).toFloat(),
                length = len + 2 * d, height = ht + 2 * d, angle = angle,
            )
            if (box.length >= 4 && box.height >= 4) boxes.add(box)
        }
        return boxes
    }

    private fun push(p: Int, prob: FloatArray, labels: IntArray, label: Int, stack: IntArray, top: Int): Int? {
        if (prob[p] <= THRESHOLD || labels[p] != 0) return null
        labels[p] = label
        stack[top] = p
        return top + 1
    }

    // ------------------------------------------------------------------ recognition

    private val cropPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private fun read(src: Bitmap, box: TextBox): OcrLine? {
        // Cut the region out straightened and scaled to the model's height
        val k = REC_HEIGHT / box.height
        val rw = min(MAX_REC_WIDTH, max(16, (box.length * k).roundToInt()))
        val crop = Bitmap.createBitmap(rw, REC_HEIGHT, Bitmap.Config.ARGB_8888)
        val matrix = Matrix().apply {
            postTranslate(-box.cx, -box.cy)
            postRotate(-Math.toDegrees(box.angle.toDouble()).toFloat())
            postScale(k, k)
            postTranslate(rw / 2f, REC_HEIGHT / 2f)
        }
        Canvas(crop).apply {
            drawColor(android.graphics.Color.WHITE)
            drawBitmap(src, matrix, cropPaint)
        }
        val px = IntArray(rw * REC_HEIGHT)
        crop.getPixels(px, 0, rw, 0, 0, rw, REC_HEIGHT)
        crop.recycle()
        val plane = rw * REC_HEIGHT
        val input = FloatBuffer.allocate(3 * plane)
        for (i in 0 until plane) {
            val c = px[i]
            input.put(i, (c and 0xFF) / 127.5f - 1f)
            input.put(plane + i, ((c shr 8) and 0xFF) / 127.5f - 1f)
            input.put(2 * plane + i, ((c shr 16) and 0xFF) / 127.5f - 1f)
        }
        var best: Reading? = null
        for (reader in readers!!) {
            input.rewind()
            val reading = OnnxTensor.createTensor(env, input, longArrayOf(1, 3, REC_HEIGHT.toLong(), rw.toLong())).use { tensor ->
                reader.session.run(mapOf(reader.session.inputNames.first() to tensor)).use { out ->
                    decode(out[0] as OnnxTensor, reader.chars)
                }
            }
            if (best == null || reading.evidence > best.evidence) best = reading
            // Letters only Cyrillic has, read surely: the Latin model would only see part of it
            if (reading.mean >= 0.85f && reading.text.count { it in CYRILLIC_ONLY } >= 2) break
        }
        val r = best ?: return null
        val text = r.text.trim()
        if (text.isEmpty()) return null
        return OcrLine(text, box.bounds(w = src.width, h = src.height), r.mean, words(r, box, src.width, src.height))
    }

    /** One model's reading of a line. */
    private class Reading(
        val text: String,
        val mean: Float,
        /** Sum of the confidence of the letters read with confidence above one half (not spaces). */
        val evidence: Float,
        /** (index in [text], time step) for each character. */
        val positions: List<Pair<Int, Int>>,
        val steps: Int,
    )

    private fun decode(o: OnnxTensor, chars: List<String>): Reading {
        val shape = o.info.shape // [1, T, C]
        val tSteps = shape[1].toInt()
        val classes = shape[2].toInt()
        val fb = o.floatBuffer
        val sb = StringBuilder()
        var sum = 0f
        var evidence = 0f
        var n = 0
        var prev = -1
        val pos = ArrayList<Pair<Int, Int>>()
        for (s in 0 until tSteps) {
            var bi = 0
            var bv = -1f
            val base = s * classes
            for (c in 0 until classes) {
                val v = fb.get(base + c)
                if (v > bv) { bv = v; bi = c }
            }
            if (bi != prev && bi != 0) {
                val ch = chars.getOrNull(bi - 1) ?: ""
                pos.add(sb.length to s)
                sb.append(ch)
                sum += bv
                n++
                if (ch.isNotBlank() && bv > 0.5f) evidence += bv
            }
            prev = bi
        }
        return Reading(sb.toString(), if (n == 0) 0f else sum / n, evidence, pos, tSteps)
    }

    /** Splits a line into words and places each one by where its letters were read. */
    private fun words(r: Reading, box: TextBox, w: Int, h: Int): List<OcrWord> {
        val text = r.text
        val result = ArrayList<OcrWord>()
        val stepOf = IntArray(text.length) { -1 }
        for ((i, s) in r.positions) if (i < text.length) stepOf[i] = s
        fun at(i: Int): Float {
            var j = i
            while (j > 0 && stepOf[j] < 0) j--
            val s = if (stepOf[j] < 0) 0 else stepOf[j]
            return s.toFloat() / max(1, r.steps)
        }
        var i = 0
        while (i < text.length) {
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length) break
            val start = i
            while (i < text.length && !text[i].isWhitespace()) i++
            val from = at(start)
            val to = if (i < text.length) at(i) else 1f
            result.add(OcrWord(text.substring(start, i), box.bounds(from, max(from + 0.001f, to), w, h)))
        }
        return result
    }

    // ------------------------------------------------------------------ reading order

    private class Row(val pieces: MutableList<OcrLine>) {
        val left get() = pieces.minOf { it.box.left }
        val top get() = pieces.minOf { it.box.top }
        val right get() = pieces.maxOf { it.box.right }
        val bottom get() = pieces.maxOf { it.box.bottom }
    }

    private fun order(pieces: List<OcrLine>): List<OcrLine> {
        // 1. Pieces side by side on one line and close together (gap under 2.5 heights) are one line
        val rows = ArrayList<Row>()
        for (p in pieces.sortedBy { it.box.left }) {
            val row = rows.firstOrNull { r ->
                val last = r.pieces.last().box
                val hgt = min(p.box.height(), last.height())
                val overlap = min(p.box.bottom, last.bottom) - max(p.box.top, last.top)
                val gap = p.box.left - last.right
                overlap > hgt / 2 && gap > -hgt && gap < 2.5f * hgt
            }
            if (row != null) row.pieces.add(p) else rows.add(Row(mutableListOf(p)))
        }
        // 2. A line joins the block whose last line is just above it and overlaps it sideways
        val blocks = ArrayList<MutableList<Row>>()
        for (r in rows.sortedBy { it.top }) {
            val hgt = r.bottom - r.top
            var best: MutableList<Row>? = null
            for (b in blocks) {
                val l = b.last()
                val lh = l.bottom - l.top
                val overlap = min(r.right, l.right) - max(r.left, l.left)
                val dy = r.top - l.bottom
                if (overlap > 0.3f * min(r.right - r.left, l.right - l.left) &&
                    dy > -0.5f * min(hgt, lh) && dy < 1.2f * max(hgt, lh) &&
                    (best == null || l.bottom > best.last().bottom)
                ) best = b
            }
            if (best != null) best.add(r) else blocks.add(mutableListOf(r))
        }
        // 3. Blocks top to bottom (as they start), then left to right
        val ordered = blocks.sortedWith(compareBy({ it.first().top }, { it.first().left }))
        val out = ArrayList<OcrLine>()
        var group = 0
        for (b in ordered) for (r in b) {
            for (p in r.pieces) out.add(p.copy(group = group))
            group++
        }
        return out
    }
}
