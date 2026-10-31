package ua.pysanka.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pysanka: one recognised word, with where it is on the image. */
data class OcrWord(val text: String, val box: Rect)

/** Pysanka: one recognised line of text. */
data class OcrLine(val text: String, val box: Rect, val confidence: Float, val words: List<OcrWord>)

/**
 * Pysanka: text recognition on the phone, with the PP-OCRv5 mobile models built into the app
 * (assets/ocr; where they come from: SOURCES.json, checksums: SHA256SUMS).
 *
 * 1. Detection finds text regions: the picture is scaled so its long side is at most 960 px (a
 *    multiple of 32), normalised, and the model gives a probability map. Pixels above 0.3 are
 *    grouped into regions; regions whose mean probability is below 0.6 are dropped; each region is
 *    grown back ("unclipped") by area × 1.5 / perimeter, because the model shrinks text areas.
 * 2. Each region is cut out, scaled to 48 px height and read by both recognition models
 *    (Cyrillic and Latin); the reading with the higher mean confidence wins, so mixed text works.
 * 3. Word positions come from where each character was read (its time step) along the line.
 *
 * Nothing leaves the phone and nothing is stored. The same steps were checked with a Python
 * prototype of this exact logic on the same model files.
 */
class TextRecognizer private constructor(private val context: Context) {
    companion object {
        private const val DIR = "ocr"
        private const val MAX_SIDE = 960
        private const val THRESHOLD = 0.3f
        private const val BOX_THRESHOLD = 0.6f
        private const val UNCLIP = 1.5f
        private const val MIN_PIXELS = 10
        private const val REC_HEIGHT = 48
        private const val MAX_REC_WIDTH = 3200
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

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
        setIntraOpNumThreads(2)
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

    /** Frees the models' memory (about 60 MB) when the screen that used them closes. */
    suspend fun release() = mutex.withLock {
        det?.close(); det = null
        readers?.forEach { it.session.close() }; readers = null
    }

    /** All text lines on [bitmap], top to bottom, left to right. Runs off the main thread. */
    suspend fun recognize(bitmap: Bitmap): List<OcrLine> = withContext(Dispatchers.Default) {
        mutex.withLock {
            load()
            val boxes = detect(bitmap)
            boxes.mapNotNull { box -> read(bitmap, box) }
                .filter { it.text.isNotBlank() }
        }
    }

    // ------------------------------------------------------------------ detection

    private fun detect(src: Bitmap): List<Rect> {
        val w = src.width
        val h = src.height
        val scale = min(1f, MAX_SIDE.toFloat() / max(w, h))
        val nw = max(32, (w * scale / 32f).roundToInt() * 32)
        val nh = max(32, (h * scale / 32f).roundToInt() * 32)
        val scaled = Bitmap.createScaledBitmap(src, nw, nh, true)
        val px = IntArray(nw * nh)
        scaled.getPixels(px, 0, nw, 0, 0, nw, nh)
        if (scaled !== src) scaled.recycle()
        // Channels in BGR order, as the model was trained (Paddle decodes images as BGR)
        val input = FloatBuffer.allocate(3 * nw * nh)
        val plane = nw * nh
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
        val prob: FloatArray = OnnxTensor.createTensor(env, input, longArrayOf(1, 3, nh.toLong(), nw.toLong())).use { t ->
            session.run(mapOf(session.inputNames.first() to t)).use { out ->
                val tensor = out[0] as OnnxTensor
                val fb = tensor.floatBuffer
                FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }
        // Group pixels above the threshold into 4-connected regions
        val labels = IntArray(plane)
        val stack = IntArray(plane)
        val boxes = ArrayList<Rect>()
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
            while (top > 0) {
                val p = stack[--top]
                val x = p % nw
                val y = p / nw
                count++
                sum += prob[p]
                if (x < x1) x1 = x; if (x > x2) x2 = x
                if (y < y1) y1 = y; if (y > y2) y2 = y
                if (x > 0) push(p - 1, prob, labels, label, stack, top)?.let { top = it }
                if (x < nw - 1) push(p + 1, prob, labels, label, stack, top)?.let { top = it }
                if (y > 0) push(p - nw, prob, labels, label, stack, top)?.let { top = it }
                if (y < nh - 1) push(p + nw, prob, labels, label, stack, top)?.let { top = it }
            }
            if (count < MIN_PIXELS || sum / count < BOX_THRESHOLD) continue
            val bw = (x2 + 1 - x1).toFloat()
            val bh = (y2 + 1 - y1).toFloat()
            val d = bw * bh * UNCLIP / (2 * (bw + bh))
            val sx = w.toFloat() / nw
            val sy = h.toFloat() / nh
            val rect = Rect(
                max(0, ((x1 - d) * sx).toInt()),
                max(0, ((y1 - d) * sy).toInt()),
                min(w, ((x2 + 1 + d) * sx).toInt()),
                min(h, ((y2 + 1 + d) * sy).toInt()),
            )
            if (rect.width() >= 4 && rect.height() >= 4) boxes.add(rect)
        }
        // Reading order: boxes that overlap vertically by more than half form a row; rows top to
        // bottom, boxes in a row left to right
        val rows = ArrayList<MutableList<Rect>>()
        for (b in boxes.sortedBy { it.top }) {
            val row = rows.lastOrNull()?.takeIf { r ->
                val top = r.minOf { it.top }
                val bottom = r.maxOf { it.bottom }
                min(bottom, b.bottom) - max(top, b.top) > min(b.height(), bottom - top) / 2
            }
            if (row != null) row.add(b) else rows.add(mutableListOf(b))
        }
        return rows.flatMap { r -> r.sortedBy { it.left } }
    }

    private fun push(p: Int, prob: FloatArray, labels: IntArray, label: Int, stack: IntArray, top: Int): Int? {
        if (prob[p] <= THRESHOLD || labels[p] != 0) return null
        labels[p] = label
        stack[top] = p
        return top + 1
    }

    // ------------------------------------------------------------------ recognition

    private fun read(src: Bitmap, box: Rect): OcrLine? {
        val crop = Bitmap.createBitmap(src, box.left, box.top, box.width(), box.height())
        val rw = min(MAX_REC_WIDTH, max(16, (crop.width * REC_HEIGHT.toFloat() / crop.height).roundToInt()))
        val scaled = Bitmap.createScaledBitmap(crop, rw, REC_HEIGHT, true)
        if (crop !== src && crop !== scaled) crop.recycle()
        val px = IntArray(rw * REC_HEIGHT)
        scaled.getPixels(px, 0, rw, 0, 0, rw, REC_HEIGHT)
        // Never the picture itself (a 48 px high picture of one line comes back unscaled)
        if (scaled !== src) scaled.recycle()
        val plane = rw * REC_HEIGHT
        val input = FloatBuffer.allocate(3 * plane)
        for (i in 0 until plane) {
            val c = px[i]
            input.put(i, (c and 0xFF) / 127.5f - 1f)
            input.put(plane + i, ((c shr 8) and 0xFF) / 127.5f - 1f)
            input.put(2 * plane + i, ((c shr 16) and 0xFF) / 127.5f - 1f)
        }
        var best: Triple<String, Float, List<Pair<Int, Int>>>? = null
        var steps = 1
        for (reader in readers!!) {
            input.rewind()
            val (text, conf, positions, t) = OnnxTensor.createTensor(env, input, longArrayOf(1, 3, REC_HEIGHT.toLong(), rw.toLong())).use { tensor ->
                reader.session.run(mapOf(reader.session.inputNames.first() to tensor)).use { out ->
                    val o = out[0] as OnnxTensor
                    val shape = o.info.shape // [1, T, C]
                    val tSteps = shape[1].toInt()
                    val classes = shape[2].toInt()
                    val fb = o.floatBuffer
                    val sb = StringBuilder()
                    var sum = 0f
                    var n = 0
                    var prev = -1
                    val pos = ArrayList<Pair<Int, Int>>() // (char index in text, time step)
                    for (s in 0 until tSteps) {
                        var bi = 0
                        var bv = -1f
                        val base = s * classes
                        for (c in 0 until classes) {
                            val v = fb.get(base + c)
                            if (v > bv) { bv = v; bi = c }
                        }
                        if (bi != prev && bi != 0) {
                            val ch = reader.chars.getOrNull(bi - 1) ?: ""
                            pos.add(sb.length to s)
                            sb.append(ch)
                            sum += bv
                            n++
                        }
                        prev = bi
                    }
                    Quad(sb.toString(), if (n == 0) 0f else sum / n, pos, tSteps)
                }
            }
            if (best == null || conf > best.second) {
                best = Triple(text, conf, positions)
                steps = t
            }
        }
        val (text, conf, positions) = best ?: return null
        return OcrLine(text.trim(), box, conf, words(text, positions, steps, box))
    }

    private data class Quad(val a: String, val b: Float, val c: List<Pair<Int, Int>>, val d: Int)

    /** Splits a line into words and places each one by where its letters were read. */
    private fun words(text: String, positions: List<Pair<Int, Int>>, steps: Int, box: Rect): List<OcrWord> {
        val result = ArrayList<OcrWord>()
        val stepOf = IntArray(text.length) { -1 }
        for ((i, s) in positions) if (i < text.length) stepOf[i] = s
        fun xAt(i: Int): Int {
            var j = i
            while (j > 0 && stepOf[j] < 0) j--
            val s = if (stepOf[j] < 0) 0 else stepOf[j]
            return box.left + (box.width() * s.toFloat() / max(1, steps)).toInt()
        }
        var i = 0
        while (i < text.length) {
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length) break
            val start = i
            while (i < text.length && !text[i].isWhitespace()) i++
            val left = xAt(start)
            val right = if (i < text.length) xAt(i) else box.right
            result.add(OcrWord(text.substring(start, i), Rect(left, box.top, max(left + 1, right), box.bottom)))
        }
        return result
    }
}
