package ua.pysanka.ocr

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
import android.view.WindowManager
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
        private const val MAX_SIDE = 2400

        fun intent(context: Context, source: String) = Intent(context, TextFromImageActivity::class.java)
            .putExtra(EXTRA_SOURCE, source)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Paired with letGo in every onDestroy, so the count is right however the screen ends
        TextRecognizer.get(this).hold()
        // What is on a photo of a document is as private as what is typed
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
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
    data object Failed : Stage
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
                    Stage.Failed
                } else {
                    val lines = TextRecognizer.get(context).recognize(bitmap)
                    if (lines.isEmpty()) Stage.Nothing else Stage.Result(bitmap, lines)
                }
            } catch (e: Throwable) {
                Stage.Failed
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
        Stage.Nothing, Stage.Failed -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringRes(if (s == Stage.Nothing) R.string.pysanka__ocr__nothing else R.string.pysanka__ocr__failed))
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

    fun selectedText(): String = lines.indices.mapNotNull { li ->
        words.withIndex().filter { it.value.first == li && it.index in selected }
            .joinToString(" ") { it.value.second.text }.takeIf { it.isNotBlank() }
    }.joinToString("\n")

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
        }
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(enabled = count > 0, modifier = Modifier.weight(1f), onClick = {
                PendingInsert.set(selectedText())
                close()
            }) { Text(stringRes(R.string.pysanka__ocr__insert)) }
            FilledTonalButton(enabled = count > 0, modifier = Modifier.weight(1f), onClick = {
                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("text", selectedText()))
                close()
            }) { Text(stringRes(R.string.pysanka__ocr__copy)) }
            OutlinedButton(enabled = count > 0, modifier = Modifier.weight(1f), onClick = {
                val clipboardManager by context.clipboardManager()
                clipboardManager.addNewPlaintext(selectedText())
                close()
            }) { Text(stringRes(R.string.pysanka__ocr__to_history)) }
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

/** Reads a picture upright and not larger than needed (a 50 MP photo would not fit in memory). */
private fun decode(context: Context, uri: Uri): Bitmap? {
    val cr = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2400) sample *= 2
    val bmp = cr.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    val rotation = runCatching { cr.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0 }.getOrDefault(0)
    val rotated = if (rotation == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
    return shrink(rotated)
}

private fun shrink(b: Bitmap): Bitmap {
    val s = 2400f / max(b.width, b.height)
    return if (s >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
}
