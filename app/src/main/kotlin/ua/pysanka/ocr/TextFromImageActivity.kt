package ua.pysanka.ocr

import ua.pysanka.privacy.followScreenshotBlock
import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.AppTheme
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
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
 * - From the camera: with "Live camera" on (and the camera permission given), the camera shows
 *   here and a tap takes the frame; otherwise the choice is recent photos or the system camera app,
 *   which hands over one picture and needs no permission either.
 * - The recognised words are highlighted; tap or swipe over them, then insert into the field the
 *   keyboard was in, copy, or keep in the clipboard history. Nothing is stored otherwise, and the
 *   screen cannot be captured in screenshots.
 */
class TextFromImageActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_SOURCE = "source"
        const val SOURCE_PHOTO = "photo"
        const val SOURCE_CAMERA = "camera"

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
    data object LiveCamera : Stage
    data object Working : Stage
    data class Result(val bitmap: Bitmap, val lines: List<OcrLine>) : Stage
    data object Nothing : Stage
    /** [reason]: the kind of error only (no content), shown so a failure can be reported. */
    data class Failed(val reason: String? = null) : Stage
}

@Composable
private fun TextFromImageScreen(source: String, close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by FlorisPreferenceStore
    val liveCameraOn = prefs.pysankaOcr.liveCamera.get()
    var stage by remember { mutableStateOf<Stage>(Stage.Choose) }

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

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) close() else recognize { decode(context, uri) }
    }
    // Without the camera permission Android does not allow "take a picture for me" (the app declares
    // the permission for the live camera). So the phone's own camera app opens; the photo goes to the
    // gallery as usual, and on return the photo picker opens with that newest photo first.
    val openCameraApp = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        stage = if (granted) Stage.LiveCamera else Stage.Choose
    }

    // Opened once, also if the screen is turned (the activity keeps itself across rotation)
    var started by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (started) return@LaunchedEffect
        started = true
        when {
            source == TextFromImageActivity.SOURCE_PHOTO ->
                pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            liveCameraOn && hasCamera(context) -> stage = Stage.LiveCamera
            liveCameraOn -> askCamera.launch(Manifest.permission.CAMERA)
            else -> stage = Stage.Choose
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
                val hasCameraApp = remember {
                    Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).resolveActivity(context.packageManager) != null
                }
                if (hasCameraApp) FilledTonalButton(
                    onClick = {
                        runCatching { openCameraApp.launch(Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringRes(R.string.pysanka__ocr__take_photo))
                }
                TextButton(onClick = close) { Text(stringRes(R.string.pysanka__ocr__close)) }
            }
        }
        Stage.LiveCamera -> LiveCamera(
            onCaptured = { bitmap -> recognize { bitmap } },
            onFailed = { stage = Stage.Choose },
            close = close,
        )
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

@Composable
private fun LiveCamera(onCaptured: (Bitmap) -> Unit, onFailed: () -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // About 2400 px on the long side is plenty for text and keeps memory and time low
    val capture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(
                androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        androidx.camera.core.resolutionselector.ResolutionStrategy(
                            android.util.Size(2400, 1800),
                            androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        ),
                    )
                    .build(),
            )
            .build()
    }
    var providerRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var busy by remember { mutableStateOf(false) }
    val background = remember { java.util.concurrent.Executors.newSingleThreadExecutor() }
    // The camera turns off as soon as this view goes (after the shot, or on close)
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            runCatching { providerRef?.unbindAll() }
            background.shutdown()
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val view = PreviewView(ctx)
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    try {
                        val provider = future.get()
                        providerRef = provider
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                    } catch (e: Exception) {
                        onFailed()
                    }
                }, ContextCompat.getMainExecutor(ctx))
                view
            },
        )
        Text(
            stringRes(R.string.pysanka__ocr__live_hint),
            color = Color.White,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(16.dp),
        )
        Row(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = close) { Text(stringRes(R.string.pysanka__ocr__close), color = Color.White) }
            Box(
                Modifier
                    .size(72.dp)
                    .background(Color.White, CircleShape)
                    .pointerInput(Unit) {
                        detectTapGestures {
                            if (busy) return@detectTapGestures
                            busy = true
                            // Converted on a background thread; the result comes back on the main one
                            capture.takePicture(background, object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    val bitmap = runCatching {
                                        val b = image.toBitmap()
                                        val rot = image.imageInfo.rotationDegrees
                                        shrink(if (rot == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot.toFloat()) }, true))
                                    }.getOrNull()
                                    image.close()
                                    ContextCompat.getMainExecutor(context).execute {
                                        runCatching { providerRef?.unbindAll() }
                                        if (bitmap != null) onCaptured(bitmap) else { busy = false; onFailed() }
                                    }
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    ContextCompat.getMainExecutor(context).execute { busy = false; onFailed() }
                                }
                            })
                        }
                    },
            )
        }
    }
}

private fun hasCamera(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

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
