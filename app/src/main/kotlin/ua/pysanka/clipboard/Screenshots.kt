package ua.pysanka.clipboard

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.clipboard.ClipboardManager
import dev.patrickgold.florisboard.ime.keyboard.IncognitoMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.florisboard.lib.android.stringRes
import ua.pysanka.ocr.TextRecognizer
import kotlin.math.max

/**
 * Pysanka: new screenshots go into the clipboard history by themselves, when the owner turns this
 * on (Settings → Clipboard) and lets Pysanka see photos.
 *
 * - Only screenshots taken after it was turned on: the newest MediaStore id is remembered when it
 *   starts, and only images with a larger id in a "Screenshots" folder are taken.
 * - Each is stored encrypted like any image in the history, tagged "Screenshots", and the text
 *   recognised on it (on the phone) is kept with it for finding it later.
 * - The system clipboard is not changed. Nothing is taken while the history or this option is off
 *   or incognito is forced on.
 */
object Screenshots {
    /** Longest recognised text kept with a screenshot. */
    const val MAX_TEXT = 20_000

    /** Colour index of the "Screenshots" tag when it is created. */
    private const val TAG_COLOR = 5

    /** At most this many screenshots per look (more at once means a restore or a copy, not screenshots). */
    private const val MAX_PER_SCAN = 10

    /** Older ones (by when they were added) are not taken. */
    private const val MAX_AGE_SECONDS = 24 * 60 * 60L

    val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private val prefs by FlorisPreferenceStore
    private val mutex = Mutex()
    @Volatile
    private var observer: ContentObserver? = null
    /** Touched only on the main thread (where the observer is called). */
    private var pending: Job? = null

    @Volatile
    private var followed: Triple<Context, ClipboardManager, CoroutineScope>? = null

    /**
     * Follows the setting for as long as [scope] lives: watches while it is on and the permission
     * is there, and stops watching when it is turned off. Called once the history is open.
     */
    fun follow(context: Context, manager: ClipboardManager, scope: CoroutineScope) {
        followed = Triple(context.applicationContext, manager, scope)
        scope.launch {
            prefs.pysankaScreenshots.enabled.asFlow().collectLatest { refresh() }
        }
    }

    /** Looks at the setting and the permission again (after the owner granted or took it back). */
    fun refresh() {
        val (app, manager, scope) = followed ?: return
        scope.launch {
            // One lock for starting, stopping and looking, so turning it off never races a look
            mutex.withLock {
                if (prefs.pysankaScreenshots.enabled.get() && hasPermission(app)) start(app, manager, scope) else stop(app)
            }
        }
    }

    private suspend fun start(context: Context, manager: ClipboardManager, scope: CoroutineScope) {
        if (observer != null) return
        if (prefs.pysankaScreenshots.lastId.get() < 0) {
            // First start: everything already on the phone stays out
            prefs.pysankaScreenshots.lastId.set(newestId(context))
        }
        val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                // The system reports a new screenshot several times while it is written; look once it settles
                pending?.cancel()
                pending = scope.launch {
                    delay(1500)
                    scan(context, manager)
                }
            }
        }
        runCatching {
            context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, o)
            observer = o
        }
        // Screenshots taken while the keyboard was not running
        scope.launch { scan(context, manager) }
    }

    private suspend fun stop(context: Context) {
        observer?.let { runCatching { context.contentResolver.unregisterContentObserver(it) } }
        observer = null
        withContext(Dispatchers.Main) { pending?.cancel(); pending = null }
        // Turned off: next time it starts afresh, so screenshots taken meanwhile stay out
        if (!prefs.pysankaScreenshots.enabled.get()) prefs.pysankaScreenshots.lastId.set(-1L)
    }

    private fun newestId(context: Context): Long = runCatching {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
            null, null, "${MediaStore.Images.Media._ID} DESC",
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
    }.getOrNull() ?: 0L

    private data class Found(val id: Long, val mime: String, val name: String)

    private fun allowed(): Boolean =
        prefs.pysankaScreenshots.enabled.get() && prefs.clipboard.historyEnabled.get() &&
            prefs.suggestion.incognitoMode.get() != IncognitoMode.FORCE_ON

    private suspend fun scan(context: Context, manager: ClipboardManager) = mutex.withLock {
        if (!allowed() || !hasPermission(context) || !manager.isHistoryOpen()) return@withLock
        val (seen, found) = findNew(context) ?: return@withLock
        // Move the mark first: a screenshot that fails to store is not tried again and again
        if (seen > prefs.pysankaScreenshots.lastId.get()) prefs.pysankaScreenshots.lastId.set(seen)
        if (found.isEmpty()) return@withLock
        val take = found.takeLast(MAX_PER_SCAN)
        val tagId = runCatching { tagId(context, manager) }.getOrNull()
        try {
            for (f in take) {
                if (!allowed()) break
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, f.id)
                val itemId = runCatching { manager.addScreenshot(context, uri, f.mime, f.name, tagId) }.getOrNull() ?: continue
                val text = runCatching { recognize(context, uri) }.getOrNull()
                if (!text.isNullOrBlank()) manager.setImageText(itemId, text.take(MAX_TEXT))
            }
        } finally {
            // The models take about 60 MB; the keyboard should not keep them
            runCatching { TextRecognizer.get(context).release() }
        }
    }

    /**
     * The largest id looked at, and the new screenshots to take, oldest first; null if the phone's
     * photos could not be read now.
     */
    private fun findNew(context: Context): Pair<Long, List<Found>>? {
        val last = prefs.pysankaScreenshots.lastId.get()
        val cols = mutableListOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED,
        )
        val where = StringBuilder("${MediaStore.Images.Media._ID} > ?")
        val args = mutableListOf(last.toString())
        if (Build.VERSION.SDK_INT >= 29) {
            cols += MediaStore.Images.Media.IS_PENDING
            where.append(" AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?")
            args += "%Screenshots%"
        } else {
            @Suppress("DEPRECATION")
            where.append(" AND ${MediaStore.Images.Media.DATA} LIKE ?")
            args += "%/Screenshots/%"
        }
        val out = ArrayList<Found>()
        var seen = last
        val now = System.currentTimeMillis() / 1000
        val cursor = runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols.toTypedArray(), where.toString(), args.toTypedArray(),
                "${MediaStore.Images.Media._ID} ASC",
            )
        }.getOrNull() ?: return null
        cursor.use { c ->
            while (c.moveToNext()) {
                // One still being written stops the look here; it is taken once it is finished
                if (Build.VERSION.SDK_INT >= 29 && c.getInt(4) != 0) break
                val id = c.getLong(0)
                seen = max(seen, id)
                val mime = c.getString(1) ?: continue
                if (!mime.startsWith("image/") || now - c.getLong(3) > MAX_AGE_SECONDS) continue
                out.add(Found(id, mime, c.getString(2) ?: "Screenshot"))
            }
        }
        return seen to out
    }

    /** The "Screenshots" tag: the one used before (even if renamed), or one with that name. */
    private suspend fun tagId(context: Context, manager: ClipboardManager): Long? {
        val known = prefs.pysankaScreenshots.tagId.get()
        if (known > 0 && manager.allTags().any { it.id == known }) return known
        val name = context.stringRes(R.string.pysanka__screenshots__tag)
        val id = manager.ensureTags(listOf(name to TAG_COLOR))[name.trim().lowercase()] ?: return null
        prefs.pysankaScreenshots.tagId.set(id)
        return id
    }

    private suspend fun recognize(context: Context, uri: Uri): String? {
        val bitmap = decode(context, uri) ?: return null
        return try {
            TextRecognizer.get(context).recognize(bitmap).joinToString("\n") { it.text }
        } finally {
            bitmap.recycle()
        }
    }

    private fun decode(context: Context, uri: Uri): Bitmap? {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2400) sample *= 2
        return cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
