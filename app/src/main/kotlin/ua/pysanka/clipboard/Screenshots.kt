package ua.pysanka.clipboard

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.florisboard.lib.android.stringRes
import ua.pysanka.ocr.TextRecognizer
import ua.pysanka.ocr.joinedText
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pysanka: new screenshots go into the clipboard history by themselves, when the owner turns this
 * on (Settings → Clipboard) and lets Pysanka see photos.
 *
 * - Only screenshots added after it was turned on ("since", in MediaStore's DATE_ADDED seconds),
 *   only from a folder named Screenshots, and only ones whose time taken matches the time added
 *   (a restored or copied old screenshot gets a new "added" time but keeps its old "taken" time).
 * - "Since" moves to now whenever adding was blocked: the permission missing, the history turned
 *   off or back on, incognito forced on or off. So what was taken meanwhile never comes in later.
 * - More than [MAX_PER_SCAN] at once means a copy or a restore, not screenshots: none is taken.
 * - Each is stored encrypted like any image in the history, tagged "Screenshots", and the text
 *   recognised on it (on the phone) is kept with it, in the same encrypted database.
 * - The system clipboard and the gallery are not changed.
 */
object Screenshots {
    /** Longest recognised text kept with a screenshot. */
    const val MAX_TEXT = 20_000

    /** Colour index of the "Screenshots" tag when it is created. */
    private const val TAG_COLOR = 5

    private const val MAX_PER_SCAN = 10

    /** How far "taken" may be from "added" (a screenshot is written within seconds). */
    private const val MAX_TAKEN_GAP_MS = 10 * 60 * 1000L

    /** Tall (scrolling) screenshots are read in parts of this height relative to their width. */
    private const val TILE_RATIO = 2
    private const val MAX_TILES = 12

    val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private val prefs by FlorisPreferenceStore

    /** One lock for starting, stopping and looking, so turning it off never races a look. */
    private val mutex = Mutex()
    @Volatile
    private var observer: ContentObserver? = null
    /** "Something changed in the photos"; many reports in a row become one look. */
    private val signals = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var followed: Triple<Context, ClipboardManager, CoroutineScope>? = null

    private fun nowSeconds() = System.currentTimeMillis() / 1000

    /** Called by the settings switch when the owner turns it on (with the permission granted). */
    suspend fun turnOn() {
        prefs.pysankaScreenshots.since.set(nowSeconds())
        prefs.pysankaScreenshots.blocked.set(false)
        prefs.pysankaScreenshots.lastId.set(0L)
        prefs.pysankaScreenshots.enabled.set(true)
        refresh()
    }

    suspend fun turnOff() {
        prefs.pysankaScreenshots.enabled.set(false)
        prefs.pysankaScreenshots.since.set(-1L)
        prefs.pysankaScreenshots.lastId.set(0L)
        refresh()
    }

    /**
     * Follows the setting for as long as [scope] lives: watches while it is on and the permission
     * is there. Called once the history is open.
     */
    fun follow(context: Context, manager: ClipboardManager, scope: CoroutineScope) {
        val app = context.applicationContext
        followed = Triple(app, manager, scope)
        // Looks, one after another; reports that arrive during a look lead to one more look
        scope.launch {
            for (s in signals) {
                delay(1500) // the system reports a new screenshot several times while it is written
                signals.tryReceive()
                // One failed look must not end the watching
                attempt { scan(app, manager) }
            }
        }
        scope.launch {
            prefs.pysankaScreenshots.enabled.asFlow().collect { refresh() }
        }
        // History or incognito changed: nothing taken before now comes in later
        scope.launch {
            combine(prefs.clipboard.historyEnabled.asFlow(), prefs.suggestion.incognitoMode.asFlow()) { a, b -> a to b }
                .distinctUntilChanged().drop(1)
                .collect { mutex.withLock { if (prefs.pysankaScreenshots.enabled.get()) prefs.pysankaScreenshots.since.set(nowSeconds()) } }
        }
    }

    /** Looks at the setting and the permission again (after the owner granted or took it back). */
    fun refresh() {
        val (app, _, scope) = followed ?: return
        scope.launch {
            mutex.withLock {
                val on = prefs.pysankaScreenshots.enabled.get()
                if (on && hasPermission(app)) {
                    start(app)
                } else {
                    stop(app)
                    // On, but the permission is gone: what is taken until it is back stays out
                    if (on) prefs.pysankaScreenshots.blocked.set(true)
                }
            }
        }
    }

    private suspend fun start(context: Context) {
        if (observer != null) return
        // Back after the permission was gone (granted again in the system settings): start from now
        if (prefs.pysankaScreenshots.since.get() < 0 || prefs.pysankaScreenshots.blocked.get()) {
            prefs.pysankaScreenshots.since.set(nowSeconds())
            prefs.pysankaScreenshots.blocked.set(false)
        }
        val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                signals.trySend(Unit)
            }
        }
        runCatching {
            context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, o)
            observer = o
        }
        // Screenshots taken while the keyboard was not running (but allowed)
        signals.trySend(Unit)
    }

    private fun stop(context: Context) {
        observer?.let { runCatching { context.contentResolver.unregisterContentObserver(it) } }
        observer = null
    }

    private data class Found(val id: Long, val mime: String)

    private fun allowed(): Boolean =
        prefs.pysankaScreenshots.enabled.get() && prefs.clipboard.historyEnabled.get() &&
            prefs.suggestion.incognitoMode.get() != IncognitoMode.FORCE_ON

    /** Like runCatching, but a cancelled job stays cancelled. */
    private inline fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }

    private suspend fun scan(context: Context, manager: ClipboardManager) = mutex.withLock {
        if (observer == null || !hasPermission(context) || !manager.isHistoryOpen()) return@withLock
        val since = prefs.pysankaScreenshots.since.get()
        if (since < 0) return@withLock
        val (seen, found) = findNew(context, since) ?: return@withLock
        // Move the mark first: a screenshot that fails to store is not tried again and again
        if (seen > prefs.pysankaScreenshots.lastId.get()) prefs.pysankaScreenshots.lastId.set(seen)
        if (!allowed()) {
            // Blocked now: these stay out for good
            prefs.pysankaScreenshots.since.set(nowSeconds())
            return@withLock
        }
        if (found.isEmpty() || found.size > MAX_PER_SCAN) return@withLock
        val tagId = attempt { tagId(context, manager) }
        try {
            for (f in found) {
                if (!allowed()) break
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, f.id)
                val itemId = attempt { manager.addScreenshot(context, uri, f.mime, tagId) } ?: continue
                val text = attempt { recognize(context, uri) }
                if (!text.isNullOrBlank()) attempt { manager.setImageText(itemId, text.take(MAX_TEXT)) }
            }
        } finally {
            // The models take about 60 MB; the keyboard does not keep them (unless a screen uses them)
            attempt { TextRecognizer.get(context).releaseIfUnused() }
        }
    }

    /**
     * The largest id looked at, and the new screenshots to take, oldest first; null if the phone's
     * photos could not be read now.
     */
    private fun findNew(context: Context, since: Long): Pair<Long, List<Found>>? {
        val last = prefs.pysankaScreenshots.lastId.get()
        val cols = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
        )
        // Rows still being written are not listed on Android 10+; they come with a later report
        val folder = if (Build.VERSION.SDK_INT >= 29) {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?" to "%Screenshots%"
        } else {
            @Suppress("DEPRECATION")
            "${MediaStore.Images.Media.DATA} LIKE ?" to "%/Screenshots/%"
        }
        val where = "${MediaStore.Images.Media._ID} > ? AND ${MediaStore.Images.Media.DATE_ADDED} >= ? AND ${folder.first}"
        val args = arrayOf(last.toString(), since.toString(), folder.second)
        val cursor = attempt {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols, where, args, "${MediaStore.Images.Media._ID} ASC",
            )
        } ?: return null
        val out = ArrayList<Found>()
        var seen = last
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                seen = max(seen, id)
                val mime = c.getString(1) ?: continue
                if (!mime.startsWith("image/")) continue
                // When it was made: time taken, or else last changed (a copied file may have neither
                // matching the time it was added)
                val madeMs = when {
                    !c.isNull(3) && c.getLong(3) > 0 -> c.getLong(3)
                    !c.isNull(4) && c.getLong(4) > 0 -> c.getLong(4) * 1000
                    else -> continue
                }
                if (abs(c.getLong(2) * 1000 - madeMs) > MAX_TAKEN_GAP_MS) continue
                out.add(Found(id, mime))
            }
        }
        return seen to out
    }

    /**
     * The "Screenshots" tag: the one made before (even if renamed). If the owner deleted it, new
     * screenshots come without a tag (they still show under the automatic "Images").
     */
    private suspend fun tagId(context: Context, manager: ClipboardManager): Long? {
        val known = prefs.pysankaScreenshots.tagId.get()
        if (known > 0) return known.takeIf { id -> manager.allTags().any { it.id == id } }
        val name = context.stringRes(R.string.pysanka__screenshots__tag)
        val id = manager.ensureTags(listOf(name to TAG_COLOR))[name.trim().lowercase()] ?: return null
        prefs.pysankaScreenshots.tagId.set(id)
        return id
    }

    /** The text on the screenshot; a tall (scrolling) one is read in parts so small text stays readable. */
    private suspend fun recognize(context: Context, uri: Uri): String? {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        val recognizer = TextRecognizer.get(context)
        if (h <= w * TILE_RATIO * 5 / 4) {
            var sample = 1
            while (max(w, h) / (sample * 2) >= 2400) sample *= 2
            val bitmap = cr.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null
            return try {
                recognizer.recognize(bitmap).joinedText()
            } finally {
                bitmap.recycle()
            }
        }
        val input = cr.openInputStream(uri) ?: return null
        return input.use { stream ->
            val decoder = if (Build.VERSION.SDK_INT >= 31) {
                BitmapRegionDecoder.newInstance(stream)
            } else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(stream, false)
            } ?: return null
            try {
                var sample = 1
                while (w / (sample * 2) >= 1200) sample *= 2
                val tile = w * TILE_RATIO
                val lines = ArrayList<String>()
                var top = 0
                var n = 0
                while (top < h && n < MAX_TILES) {
                    val part = decoder.decodeRegion(
                        Rect(0, top, w, min(h, top + tile)), BitmapFactory.Options().apply { inSampleSize = sample },
                    )
                    if (part != null) {
                        try {
                            recognizer.recognize(part).joinedText().takeIf { it.isNotBlank() }?.let { lines.add(it) }
                        } finally {
                            part.recycle()
                        }
                    }
                    top += tile
                    n++
                }
                lines.joinToString("\n")
            } finally {
                decoder.recycle()
            }
        }
    }
}
