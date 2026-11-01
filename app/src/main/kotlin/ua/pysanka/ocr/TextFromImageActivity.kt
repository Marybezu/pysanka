package ua.pysanka.ocr

import ua.pysanka.privacy.followScreenshotBlock
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.AppTheme
import dev.patrickgold.florisboard.app.apptheme.FlorisAppTheme
import dev.patrickgold.florisboard.clipboardManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.compose.stringRes
import kotlin.math.max
import kotlin.math.min

/**
 * Pysanka: "text from a photo / from the camera". Opened from the bar above the keyboard.
 *
 * - From a photo: the system photo picker (opens on recent photos); the app gets only that photo,
 *   no permission is needed.
 * - From the camera: the phone's own camera app takes one picture for Pysanka (Pysanka has no
 *   camera permission at all). The picture is not saved in the gallery: it comes back into a file
 *   in Pysanka's cache, is read and deleted at once.
 * - The recognised words are highlighted; tap or swipe over them, then insert into the field the
 *   keyboard was in, copy, or keep in the clipboard history. Nothing is stored otherwise, and the
 *   screen cannot be captured in screenshots.
 */
class TextFromImageActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_SOURCE = "source"
        const val SOURCE_PHOTO = "photo"
        const val SOURCE_CAMERA = "camera"
        /** A photo picked into the clipboard history (with its text kept for finding it later). */
        const val SOURCE_HISTORY = "history"

        fun intent(context: Context, source: String) = Intent(context, TextFromImageActivity::class.java)
            .putExtra(EXTRA_SOURCE, source)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Paired with letGo in every onDestroy, so the count is right however the screen ends
        TextRecognizer.get(this).hold()
        // Blocked from screenshots only if the owner chose so (Privacy)
        followScreenshotBlock()
        // Never a picture of this screen in the recent apps (it is left out of them anyway)
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: SOURCE_PHOTO
        setContent {
            // The app's texts (stringRes) are read through this; without it the screen fails at once
            org.florisboard.lib.compose.ProvideLocalizedResources(this, appName = dev.patrickgold.florisboard.R.string.app_name) {
                FlorisAppTheme(theme = AppTheme.AUTO) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        TextFromImageScreen(source) { finish() }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // The models take about 60 MB; freed when no screen uses them (a rotation holds them again at once)
        val recognizer = TextRecognizer.get(this)
        kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch { recognizer.letGo() }
    }
}

private sealed interface Stage {
    data object Choose : Stage
    data object Working : Stage
    data object Waiting : Stage
    data class Result(val bitmap: Bitmap, val lines: List<OcrLine>) : Stage
    data object Nothing : Stage
    /** [reason]: the kind of error only (no content), shown so a failure can be reported. */
    data class Failed(val reason: String? = null) : Stage
}

@Composable
private fun TextFromImageScreen(source: String, close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // From the camera: nothing to show while the camera app opens (not "closed without a photo")
    var stage by remember { mutableStateOf<Stage>(if (source == TextFromImageActivity.SOURCE_CAMERA) Stage.Waiting else Stage.Choose) }

    fun recognize(load: suspend () -> Bitmap?) {
        stage = Stage.Working
        scope.launch {
            stage = try {
                val bitmap = withContext(Dispatchers.IO) { load() }
                if (bitmap == null) {
                    Stage.Failed("no picture")
                } else {
                    val lines = TextRecognizer.get(context).recognize(bitmap)
                    if (lines.isEmpty()) Stage.Nothing else Stage.Result(bitmap, lines)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.w("Pysanka", "Text from image failed: ${e.javaClass.simpleName}")
                Stage.Failed(e.javaClass.simpleName)
            }
        }
    }

    // Into the clipboard history: stored encrypted at once, then its text is read for finding it later
    fun toHistory(uri: Uri) {
        stage = Stage.Working
        scope.launch {
            val clipboardManager by context.clipboardManager()
            val id = withContext(Dispatchers.IO) { runCatching { clipboardManager.addImage(context, uri) }.getOrNull() }
            if (id == null) {
                stage = Stage.Failed("not added")
                return@launch
            }
            android.widget.Toast.makeText(context, R.string.pysanka__photo_history__added, android.widget.Toast.LENGTH_SHORT).show()
            val text = runCatching {
                withContext(Dispatchers.IO) { decode(context, uri) }?.let { b ->
                    TextRecognizer.get(context).recognize(b).joinedText()
                }
            }.getOrNull()
            if (!text.isNullOrBlank()) {
                withContext(Dispatchers.IO) {
                    runCatching { clipboardManager.setImageText(id, text.take(dev.patrickgold.florisboard.ime.clipboard.ClipboardManager.MAX_IMAGE_TEXT)) }
                }
            }
            close()
        }
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        when {
            uri == null -> close()
            source == TextFromImageActivity.SOURCE_HISTORY -> toHistory(uri)
            else -> recognize { decode(context, uri) }
        }
    }
    // The phone's camera app takes the picture into Pysanka's file; nothing goes to the gallery
    var shot by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val uri = shot
        if (taken && uri != null) {
            recognize { try { decode(context, uri) } finally { CameraShot.delete(context, uri) } }
        } else {
            CameraShot.delete(context, uri)
            stage = Stage.Choose
        }
        shot = null
    }
    fun openCamera() {
        val uri = CameraShot.newShot(context)
        shot = uri
        try {
            takePicture.launch(uri)
        } catch (e: android.content.ActivityNotFoundException) {
            // No camera app: recent photos instead
            CameraShot.delete(context, uri)
            shot = null
            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }
    // This screen's shot never stays behind once the screen really closes (not on a mere recreation,
    // while the camera app may still be writing it)
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            if ((context as? android.app.Activity)?.isFinishing == true) CameraShot.delete(context, shot)
        }
    }

    // Opened once, also if the screen is turned (the activity keeps itself across rotation)
    var started by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (started) return@LaunchedEffect
        started = true
        if (source != TextFromImageActivity.SOURCE_CAMERA) {
            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } else {
            openCamera()
        }
    }

    when (val s = stage) {
        Stage.Choose -> if (source == TextFromImageActivity.SOURCE_CAMERA) {
            Column(
                Modifier.fillMaxSize().padding(24.dp).statusBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringRes(R.string.pysanka__ocr__choose_title), style = MaterialTheme.typography.titleLarge)
                Text(stringRes(R.string.pysanka__ocr__choose_hint), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringRes(R.string.pysanka__ocr__recent_photos))
                }
                FilledTonalButton(onClick = { openCamera() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringRes(R.string.pysanka__ocr__take_photo))
                }
                TextButton(onClick = close) { Text(stringRes(R.string.pysanka__ocr__close)) }
            }
        }
        Stage.Waiting -> Box(Modifier.fillMaxSize())
        Stage.Working -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text(stringRes(R.string.pysanka__ocr__working))
            }
        }
        Stage.Nothing, is Stage.Failed -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringRes(if (s == Stage.Nothing) R.string.pysanka__ocr__nothing else R.string.pysanka__ocr__failed))
            // Only the kind of error (e.g. OutOfMemoryError), so it can be reported
            (s as? Stage.Failed)?.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(onClick = close) { Text(stringRes(R.string.pysanka__ocr__close)) }
        }
        is Stage.Result -> ResultView(s.bitmap, s.lines, close)
    }
}

@Composable
private fun ResultView(bitmap: Bitmap, lines: List<OcrLine>, close: () -> Unit) {
    val context = LocalContext.current
    val words = remember(lines) { lines.flatMapIndexed { li, l -> l.words.map { li to it } } }
    // Everything is selected at first: the most common wish is "all of it"
    var selected by remember(lines) { mutableStateOf(words.indices.toSet()) }
    var dragAdds by remember { mutableStateOf(true) }

    // Pieces of one line (same group) with spaces, lines with line breaks
    fun selectedText(): String = joinOcrLines(lines.map { it.group }.distinct().mapNotNull { g ->
        words.withIndex().filter { lines[it.value.first].group == g && it.index in selected }
            .joinToString(" ") { it.value.second.text }.takeIf { it.isNotBlank() }
    })

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
            val density = LocalDensity.current
            val boxW = with(density) { maxWidth.toPx() }
            val boxH = with(density) { maxHeight.toPx() }
            val scale = min(boxW / bitmap.width, boxH / bitmap.height)
            val ox = (boxW - bitmap.width * scale) / 2
            val oy = (boxH - bitmap.height * scale) / 2
            fun hit(p: Offset): Int? = words.indices.firstOrNull { i ->
                val r = words[i].second.box
                p.x >= ox + r.left * scale && p.x <= ox + r.right * scale && p.y >= oy + r.top * scale && p.y <= oy + r.bottom * scale
            }
            Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            val accent = MaterialTheme.colorScheme.primary
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(words) {
                        detectTapGestures { p -> hit(p)?.let { i -> selected = if (i in selected) selected - i else selected + i } }
                    }
                    .pointerInput(words) {
                        detectDragGestures(
                            onDragStart = { p -> hit(p)?.let { i -> dragAdds = i !in selected; selected = if (dragAdds) selected + i else selected - i } },
                            onDrag = { change, _ ->
                                hit(change.position)?.let { i -> selected = if (dragAdds) selected + i else selected - i }
                            },
                        )
                    },
            ) {
                for ((i, w) in words.withIndex()) {
                    val r = w.second.box
                    val on = i in selected
                    drawRoundRect(
                        color = if (on) accent.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.18f),
                        topLeft = Offset(ox + r.left * scale, oy + r.top * scale),
                        size = Size(r.width() * scale, r.height() * scale),
                        cornerRadius = CornerRadius(6f, 6f),
                    )
                }
            }
        }
        val count = selected.size
        Text(
            stringRes(R.string.pysanka__ocr__selected, "n" to count.toString(), "all" to words.size.toString()),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { selected = words.indices.toSet() }) { Text(stringRes(R.string.pysanka__ocr__all)) }
            TextButton(onClick = { selected = emptySet() }) { Text(stringRes(R.string.pysanka__ocr__none)) }
            // Pysanka: the AI fixes recognition errors, if the model is installed
            val aiReady = remember { ua.pysanka.ai.ModelStore(context).modelFile() != null }
            if (aiReady) {
                TextButton(enabled = selected.isNotEmpty(), onClick = {
                    ua.pysanka.ai.AiBridge.openFromPhoto(context, selectedText())
                    close()
                }) { Text(stringRes(R.string.pysanka__ai__fix_with_ai)) }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 8.dp)
            Button(enabled = count > 0, modifier = Modifier.weight(1f), contentPadding = pad, onClick = {
                PendingInsert.set(selectedText())
                close()
            }) { Text(stringRes(R.string.pysanka__ocr__insert), maxLines = 1, softWrap = false) }
            FilledTonalButton(enabled = count > 0, modifier = Modifier.weight(1f), contentPadding = pad, onClick = {
                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("text", selectedText()))
                close()
            }) { Text(stringRes(R.string.pysanka__ocr__copy), maxLines = 1, softWrap = false) }
            OutlinedButton(enabled = count > 0, modifier = Modifier.weight(1f), contentPadding = pad, onClick = {
                val clipboardManager by context.clipboardManager()
                clipboardManager.addNewPlaintext(selectedText())
                close()
            }) { Text(stringRes(R.string.pysanka__ocr__to_history), maxLines = 1, softWrap = false) }
        }
    }
}

/**
 * Where the system camera app puts the photo: a file in Pysanka's cache, shared with that app only
 * for this one shot. It is deleted as soon as the photo is read (and whenever the screen closes).
 */
internal object CameraShot {
    private const val DIR = "capture"

    /** Not in the cache: the keyboard empties that when its process starts, maybe mid-shot. */
    private fun dir(context: Context) = java.io.File(context.filesDir, DIR).apply { mkdirs() }

    /** A new file for one shot (its own name, so a screen closing late never deletes another's). */
    fun newShot(context: Context): Uri {
        // Shots left by a crash or a killed screen go now
        val now = System.currentTimeMillis()
        dir(context).listFiles()?.forEach { if (now - it.lastModified() > 10 * 60 * 1000L) it.delete() }
        val f = java.io.File(dir(context), "shot-$now.jpg")
        return androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider.file", f)
    }

    fun delete(context: Context, uri: Uri?) {
        val name = uri?.lastPathSegment ?: return
        java.io.File(dir(context), name.substringAfterLast('/')).delete()
    }
}

/**
 * Reads a picture upright and not larger than needed. A phone photo can be 50 MP (about 200 MB as
 * pixels), far more than the keyboard may use, so it is decoded already reduced: by the system's
 * decoder straight to the size needed (Android 9+), or else by a power of two that stays at most
 * about 3600 px, then scaled.
 */
private fun decode(context: Context, uri: Uri): Bitmap? {
    val cr = context.contentResolver
    if (android.os.Build.VERSION.SDK_INT >= 28) {
        val source = android.graphics.ImageDecoder.createSource(cr, uri)
        // Upright by itself (it applies the photo's rotation); software pixels, so they can be read
        return android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
            val w = info.size.width
            val h = info.size.height
            val s = MAX_PICTURE.toFloat() / max(w, h)
            if (s < 1f) decoder.setTargetSize(max(1, (w * s).toInt()), max(1, (h * s).toInt()))
        }.let { b ->
            if (b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, false).also { b.recycle() }
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_PICTURE * 3 / 2) sample *= 2
    val bmp = cr.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    val small = shrink(bmp)
    if (small !== bmp) bmp.recycle()
    val rotation = runCatching { cr.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0 }.getOrDefault(0)
    if (rotation == 0) return small
    val rotated = Bitmap.createBitmap(small, 0, 0, small.width, small.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
    if (rotated !== small) small.recycle()
    return rotated
}

/** Longest side of a picture to read (detection uses up to 2048 px of it). */
private const val MAX_PICTURE = 2400

private fun shrink(b: Bitmap): Bitmap {
    val s = MAX_PICTURE.toFloat() / max(b.width, b.height)
    return if (s >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
}
