package ua.pysanka.clipboard

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.ime.clipboard.ClipboardManager
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardFileInfo
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardFileStorage
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardMediaProvider
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.json.JSONArray
import org.json.JSONObject
import ua.pysanka.security.SealedFile
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.spec.SecretKeySpec

/**
 * Pysanka: a copy of the clipboard history (text and images), and import of the clipboard from a
 * FlorisBoard backup.
 *
 * A Pysanka copy is always protected with a password: the history can hold passwords and codes.
 * File: format byte, key-settings byte, 16-byte salt, then a [SealedFile] stream (AES-256-GCM in
 * chunks, starting with "PYS1") keyed by Argon2id of the password, holding a ZIP with
 * `items.json` and `images/<n>`. Nothing in plain text is written to the phone's storage.
 *
 * A FlorisBoard backup is a plain ZIP (clipboard/clipboard_text_items.json,
 * clipboard/clipboard_images.json, clipboard/clipboard_files/<id>); only its clipboard is read.
 *
 * Imports read the file twice: first the list, then only the images it names, within a total size
 * budget, so a damaged or hostile file cannot fill the phone.
 *
 * Work runs in [scope], not in the screen, so turning the phone or leaving the screen does not
 * stop it halfway; the screen shows [status].
 */
object ClipboardBackup {
    const val MIN_PASSWORD_LENGTH = 12
    const val FILE_EXTENSION = "pysanka"

    private const val FORMAT: Byte = 1
    private const val KDF_PROFILE: Byte = 1
    private const val SALT_BYTES = 16
    private const val HEADER_BYTES = 2 + SALT_BYTES
    private val LABEL = "pysanka/clipboard-copy/v1".toByteArray()
    private const val ITEMS = "items.json"
    private const val IMAGES = "images/"

    /** Argon2id settings of [KDF_PROFILE] 1: a few seconds on a phone, very costly to guess at scale. */
    private const val ARGON_MEMORY_KIB = 64 * 1024
    private const val ARGON_ITERATIONS = 3

    private const val MAX_ITEMS = 5_000
    private const val MAX_JSON_BYTES = 8L * 1024 * 1024
    private const val MAX_TOTAL_IMAGE_BYTES = 1024L * 1024 * 1024

    enum class Kind { PYSANKA, FLORISBOARD, UNKNOWN }

    data class Options(val text: Boolean, val images: Boolean, val pinnedOnly: Boolean)

    sealed interface Status {
        data object Idle : Status
        data object Working : Status
        data class Exported(val count: Int) : Status
        data class Imported(val added: Int, val skipped: Int, val overLimit: Int) : Status
        data object WrongPassword : Status
        data object NotACopy : Status
        data object HistoryOff : Status
        data object Failed : Status
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    /**
     * The export password, kept in memory only while the system "save as" screen is open, so it is
     * not lost if the settings screen is recreated meanwhile. Never saved anywhere.
     */
    @Volatile
    private var pendingExport: Pair<CharArray, Options>? = null

    fun prepareExport(password: CharArray, options: Options) {
        pendingExport?.first?.fill(' ')
        pendingExport = password to options
    }

    fun cancelExport() {
        pendingExport?.first?.fill(' ')
        pendingExport = null
    }

    fun clearStatus() {
        if (_status.value != Status.Working) _status.value = Status.Idle
    }

    private class WrongPasswordOrDamaged : Exception()

    // ---------------------------------------------------------------- detect

    /** What kind of file [uri] is. Reads a few bytes; call off the main thread. */
    fun detect(context: Context, uri: Uri): Kind = runCatching {
        context.contentResolver.openInputStream(uri)!!.use { input ->
            val head = ByteArray(HEADER_BYTES + 4)
            val n = SealedFile.readFully(input, head)
            when {
                n >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
                    head[2].toInt() == 3 && head[3].toInt() == 4 -> Kind.FLORISBOARD
                n == head.size && head[0] == FORMAT && head[1] == KDF_PROFILE &&
                    String(head, HEADER_BYTES, 4, Charsets.US_ASCII) == "PYS1" -> Kind.PYSANKA
                else -> Kind.UNKNOWN
            }
        }
    }.getOrDefault(Kind.UNKNOWN)

    /** Detects in the background; [onResult] runs on the main thread. */
    fun detectAsync(context: Context, uri: Uri, onResult: (Kind) -> Unit) {
        val app = context.applicationContext
        scope.launch {
            val kind = detect(app, uri)
            kotlinx.coroutines.withContext(Dispatchers.Main) { onResult(kind) }
        }
    }

    // ---------------------------------------------------------------- export

    /** Writes the copy to [uri] with the password from [prepareExport]. */
    fun startExport(context: Context, uri: Uri) {
        val app = context.applicationContext
        val (password, options) = pendingExport ?: return
        pendingExport = null
        _status.value = Status.Working
        scope.launch {
            _status.value = try {
                Status.Exported(export(app, uri, password, options))
            } catch (t: Throwable) {
                // A half-written file is useless and could later look like a wrong password
                runCatching { android.provider.DocumentsContract.deleteDocument(app.contentResolver, uri) }
                Status.Failed
            } finally {
                password.fill(' ')
            }
        }
    }

    private suspend fun export(context: Context, uri: Uri, password: CharArray, options: Options): Int {
        require(password.size >= MIN_PASSWORD_LENGTH)
        val clipboardManager by context.clipboardManager()
        val items = clipboardManager.currentHistory.all.filter { item ->
            (!options.pinnedOnly || item.isPinned) && when (item.type) {
                ItemType.TEXT -> options.text && item.text != null
                ItemType.IMAGE -> options.images && item.uri != null
                ItemType.VIDEO -> false
            }
        }
        // The owner's tags travel with the copy, by name (ids differ from phone to phone)
        val tagList = clipboardManager.allTags()
        val tagNames = tagList.associate { it.id to it.name }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)
        var count = 0
        try {
            openOutput(context, uri).use { raw ->
                raw.write(byteArrayOf(FORMAT, KDF_PROFILE))
                raw.write(salt)
                ZipOutputStream(SealedFile.writer(SecretKeySpec(key, "AES"), LABEL, raw)).use { zip ->
                    val list = JSONArray()
                    var n = 0
                    for (item in items) {
                        currentCoroutineContext().ensureActive()
                        val o = JSONObject()
                            .put("pinned", item.isPinned)
                            .put("time", item.creationTimestampMs)
                            .put("sensitive", item.isSensitive)
                            .put("mime", JSONArray(item.mimeTypes))
                        item.tagIds().mapNotNull { tagNames[it] }.takeIf { it.isNotEmpty() }?.let { o.put("tags", JSONArray(it)) }
                        if (item.type == ItemType.TEXT) {
                            o.put("type", "text").put("text", item.text)
                        } else {
                            // An image that cannot be read (its file is gone) is left out; an error
                            // while writing the copy is not hidden and fails the export
                            val source = runCatching {
                                ClipboardFileStorage.openStream(context, ContentUris.parseId(item.uri!!))
                            }.getOrNull() ?: continue
                            val name = IMAGES + (n++)
                            source.use { input ->
                                zip.putNextEntry(ZipEntry(name))
                                input.copyTo(zip)
                                zip.closeEntry()
                            }
                            o.put("type", "image").put("file", name)
                        }
                        list.put(o)
                        count++
                    }
                    zip.putNextEntry(ZipEntry(ITEMS))
                    val tagDefs = JSONArray(tagList.map { JSONObject().put("name", it.name).put("color", it.color) })
                    zip.write(JSONObject().put("format", 1).put("tags", tagDefs).put("items", list).toString().toByteArray())
                    zip.closeEntry()
                }
            }
        } finally {
            key.fill(0)
        }
        return count
    }

    private fun openOutput(context: Context, uri: Uri): OutputStream =
        runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: context.contentResolver.openOutputStream(uri, "w")!!

    // ---------------------------------------------------------------- import

    /** Imports a Pysanka copy (with [password]) or a FlorisBoard backup ([password] null). */
    fun startImport(context: Context, uri: Uri, kind: Kind, password: CharArray?) {
        val app = context.applicationContext
        _status.value = Status.Working
        scope.launch {
            _status.value = try {
                val r = when (kind) {
                    Kind.FLORISBOARD -> importFloris(app, uri)
                    Kind.PYSANKA -> importPysanka(app, uri, requireNotNull(password))
                    Kind.UNKNOWN -> throw IOException()
                }
                Status.Imported(r.added, r.skipped, r.overLimit)
            } catch (e: WrongPasswordOrDamaged) {
                Status.WrongPassword
            } catch (e: ClipboardManager.HistoryDisabled) {
                Status.HistoryOff
            } catch (t: Throwable) {
                Status.Failed
            } finally {
                password?.fill(' ')
            }
        }
    }

    /** One item read from a file, before anything is added. */
    private class Candidate(
        val item: ClipboardItem, val file: String?, val displayName: String, val tagNames: List<String> = emptyList(),
    ) {
        var stored: ClipboardFileStorage.Cloned? = null
    }

    private suspend fun importPysanka(context: Context, uri: Uri, password: CharArray): ClipboardManager.ImportOutcome {
        val salt = context.contentResolver.openInputStream(uri)!!.use { input ->
            val head = ByteArray(HEADER_BYTES)
            if (SealedFile.readFully(input, head) != HEADER_BYTES || head[0] != FORMAT || head[1] != KDF_PROFILE) {
                throw WrongPasswordOrDamaged()
            }
            head.copyOfRange(2, HEADER_BYTES)
        }
        val key = SecretKeySpec(deriveKey(password, salt), "AES")
        // Pass 1: the list, and a check of the whole file (to its last chunk)
        val json = try {
            readItems(context, uri, key)
        } catch (e: IOException) {
            throw WrongPasswordOrDamaged()
        } ?: throw WrongPasswordOrDamaged()
        val list = json.optJSONArray("items") ?: throw WrongPasswordOrDamaged()
        val candidates = ArrayList<Candidate>()
        val files = HashSet<String>()
        val tagDefs = json.optJSONArray("tags")?.let { a ->
            (0 until minOf(a.length(), 200)).mapNotNull { a.optJSONObject(it) }
                .map { it.optString("name").take(40) to it.optInt("color") }.filter { it.first.isNotBlank() }
        } ?: emptyList()
        for (i in 0 until minOf(list.length(), MAX_ITEMS)) {
            val o = list.optJSONObject(i) ?: continue
            val time = o.optLong("time", System.currentTimeMillis())
            val pinned = o.optBoolean("pinned")
            val sensitive = o.optBoolean("sensitive")
            val names = o.optJSONArray("tags")?.let { a -> (0 until minOf(a.length(), 50)).map { a.optString(it) } } ?: emptyList()
            when (o.optString("type")) {
                "text" -> if (o.has("text") && !o.isNull("text")) {
                    candidates.add(Candidate(textItem(o.optString("text"), time, pinned, sensitive), null, "", names))
                }
                "image" -> {
                    val name = o.optString("file")
                    // The same file named twice is used once
                    if (!name.startsWith(IMAGES) || !files.add(name)) continue
                    val mime = o.optJSONArray("mime")?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList()
                    candidates.add(Candidate(imageItem(mime, time, pinned, sensitive), name, "Image", names))
                }
            }
        }
        // Pass 2: only the images the list names
        storeImages(context, candidates) { wanted -> readZipEntries(context, uri, wanted, nameOf = { it }) { raw -> openPysanka(raw, key) } }
        return add(context, candidates, tagDefs)
    }

    private suspend fun importFloris(context: Context, uri: Uri): ClipboardManager.ImportOutcome {
        var texts: JSONArray? = null
        var images: JSONArray? = null
        context.contentResolver.openInputStream(uri)!!.use { input ->
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.removePrefix("/")
                    when {
                        name.endsWith("clipboard/clipboard_text_items.json") -> texts = JSONArray(readLimited(zip, MAX_JSON_BYTES))
                        name.endsWith("clipboard/clipboard_images.json") -> images = JSONArray(readLimited(zip, MAX_JSON_BYTES))
                    }
                }
            }
        }
        if (texts == null && images == null) throw IOException("No clipboard in this backup")
        val candidates = ArrayList<Candidate>()
        texts?.let { a ->
            for (i in 0 until minOf(a.length(), MAX_ITEMS)) {
                val o = a.optJSONObject(i) ?: continue
                if (florisType(o) != ItemType.TEXT || !o.has("text") || o.isNull("text")) continue
                candidates.add(
                    Candidate(textItem(o.optString("text"), o.optLong("creationTimestampMs", System.currentTimeMillis()),
                        o.optBoolean("isPinned"), o.optBoolean("isSensitive")), null, ""),
                )
            }
        }
        val files = HashSet<String>()
        images?.let { a ->
            for (i in 0 until minOf(a.length(), MAX_ITEMS - candidates.size)) {
                val o = a.optJSONObject(i) ?: continue
                if (florisType(o) != ItemType.IMAGE) continue
                val oldId = o.optString("uri").substringAfterLast('/')
                if (oldId.isEmpty() || oldId.toLongOrNull() == null || !files.add(oldId)) continue
                val mime = o.optJSONArray("mimeTypes")?.let { m -> List(m.length()) { m.optString(it) } } ?: emptyList()
                candidates.add(
                    Candidate(imageItem(mime, o.optLong("creationTimestampMs", System.currentTimeMillis()),
                        o.optBoolean("isPinned"), o.optBoolean("isSensitive")), oldId, "Image"),
                )
            }
        }
        storeImages(context, candidates) { wanted ->
            readZipEntries(context, uri, wanted, nameOf = { n -> if (n.contains("clipboard/clipboard_files/")) n.substringAfterLast('/') else null }) { raw ->
                Opened(ZipInputStream(BufferedInputStream(raw)), null)
            }
        }
        return add(context, candidates)
    }

    /**
     * Stores the images the candidates name, within [MAX_TOTAL_IMAGE_BYTES]. [read] walks the file
     * and stores each wanted entry; on any failure every file stored so far is removed.
     */
    private suspend fun storeImages(context: Context, candidates: List<Candidate>, read: suspend (Map<String, Candidate>) -> Unit) {
        val wanted = candidates.filter { it.file != null }.associateBy { it.file!! }
        if (wanted.isEmpty()) return
        try {
            read(wanted)
        } catch (t: Throwable) {
            candidates.forEach { c -> c.stored?.let { ClipboardFileStorage.deleteById(context, it.id) }; c.stored = null }
            throw t
        }
    }

    /** An opened ZIP and the stream under it that must be read to its end (null for a plain ZIP). */
    private class Opened(val zip: ZipInputStream, val tail: InputStream?)

    private suspend fun readZipEntries(
        context: Context,
        uri: Uri,
        wanted: Map<String, Candidate>,
        nameOf: (String) -> String?,
        open: (InputStream) -> Opened,
    ) {
        var total = 0L
        context.contentResolver.openInputStream(uri)!!.use { raw ->
            val opened = open(raw)
            opened.zip.use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val c = nameOf(entry.name.removePrefix("/"))?.let { wanted[it] } ?: continue
                    if (c.stored != null || entry.size > ClipboardFileStorage.MAX_IMAGE_BYTES) continue
                    if (total >= MAX_TOTAL_IMAGE_BYTES) break
                    val cloned = runCatching { ClipboardFileStorage.storeStream(context, zip) }.getOrNull() ?: continue
                    c.stored = cloned
                    total += cloned.size
                }
                opened.tail?.let { drain(it) }
            }
        }
    }

    private fun openPysanka(raw: InputStream, key: SecretKeySpec): Opened {
        if (SealedFile.readFully(raw, ByteArray(HEADER_BYTES)) != HEADER_BYTES) throw WrongPasswordOrDamaged()
        val sealed = SealedFile.open(key, LABEL, BufferedInputStream(raw))
        return Opened(ZipInputStream(sealed), sealed)
    }

    /** Pass 1 of a Pysanka copy: the list, after reading (and so checking) the whole file. */
    private suspend fun readItems(context: Context, uri: Uri, key: SecretKeySpec): JSONObject? {
        var result: JSONObject? = null
        context.contentResolver.openInputStream(uri)!!.use { raw ->
            val opened = openPysanka(raw, key)
            opened.zip.use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    if (entry.name == ITEMS && result == null) result = JSONObject(readLimited(zip, MAX_JSON_BYTES))
                }
                drain(opened.tail!!)
            }
        }
        return result
    }

    /** Reads what is left after the ZIP's own end, so the last encrypted chunk is verified too. */
    private fun drain(input: InputStream) {
        val buf = ByteArray(16 * 1024)
        while (input.read(buf) >= 0) Unit
    }

    private suspend fun add(
        context: Context, candidates: List<Candidate>, tagDefs: List<Pair<String, Int>> = emptyList(),
    ): ClipboardManager.ImportOutcome {
        val clipboardManager by context.clipboardManager()
        // An image whose file did not make it (missing, too large, over budget) is not added
        val (usable, missing) = candidates.partition { it.file == null || it.stored != null }
        val skippedMissing = missing.size
        val outcome = try {
            // Tags named in the copy are matched to the owner's tags by name, or created
            val tagIds = if (candidates.any { it.tagNames.isNotEmpty() }) {
                val wantedNames = candidates.flatMap { it.tagNames }.toSet()
                val defs = tagDefs + wantedNames.filter { n -> tagDefs.none { it.first.equals(n, ignoreCase = true) } }.map { it to 0 }
                clipboardManager.ensureTags(defs)
            } else {
                emptyMap()
            }
            importAll(context, clipboardManager, usable, tagIds)
        } catch (t: Throwable) {
            // Files not yet handed to the history are removed
            candidates.forEach { c -> c.stored?.let { ClipboardFileStorage.deleteById(context, it.id) }; c.stored = null }
            throw t
        }
        return outcome.copy(skipped = outcome.skipped + skippedMissing)
    }

    private suspend fun importAll(
        context: Context, clipboardManager: ClipboardManager, usable: List<Candidate>, tagIds: Map<String, Long>,
    ): ClipboardManager.ImportOutcome {
        val provider = provider(context)
        return clipboardManager.importCandidates(
            candidates = usable,
            item = { it.item },
            materialize = { c ->
                val stored = c.stored
                val ids = c.tagNames.mapNotNull { tagIds[it.trim().lowercase()] }
                val base = if (ids.isEmpty()) c.item else c.item.withTagIds(ids)
                if (stored == null) {
                    base
                } else {
                    val uri = provider.registerImported(ClipboardFileInfo(stored.id, c.displayName, stored.size, 0, c.item.mimeTypes))
                    c.stored = null // now owned by the history
                    base.copy(uri = uri)
                }
            },
            discard = { c -> c.stored?.let { ClipboardFileStorage.deleteById(context, it.id) }; c.stored = null },
        )
    }

    /** FlorisBoard writes the type by name ("TEXT") in current versions; older ones may use a number. */
    private fun florisType(o: JSONObject): ItemType? = when (val t = o.opt("type")) {
        is String -> ItemType.entries.firstOrNull { it.name.equals(t, ignoreCase = true) }
        is Number -> ItemType.entries.firstOrNull { it.value == t.toInt() }
        else -> null
    }

    private fun textItem(text: String, time: Long, pinned: Boolean, sensitive: Boolean) = ClipboardItem(
        type = ItemType.TEXT, text = text, uri = null, creationTimestampMs = time, isPinned = pinned,
        mimeTypes = listOf("text/plain"), isSensitive = sensitive,
    )

    private fun imageItem(mime: List<String>, time: Long, pinned: Boolean, sensitive: Boolean) = ClipboardItem(
        type = ItemType.IMAGE, text = null, uri = null, creationTimestampMs = time, isPinned = pinned,
        mimeTypes = mime.filter { it.startsWith("image/") }.ifEmpty { listOf("image/png") }, isSensitive = sensitive,
    )

    private fun provider(context: Context): ClipboardMediaProvider {
        val client = checkNotNull(context.contentResolver.acquireContentProviderClient(ClipboardMediaProvider.AUTHORITY))
        try {
            return client.localContentProvider as ClipboardMediaProvider
        } finally {
            client.close()
        }
    }

    private fun readLimited(input: InputStream, max: Long): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw IOException("Too large")
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
        val bytes = utf8(password)
        try {
            val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB(ARGON_MEMORY_KIB)
                .withIterations(ARGON_ITERATIONS)
                .withParallelism(1)
                .build()
            val gen = Argon2BytesGenerator().apply { init(params) }
            return ByteArray(32).also { gen.generateBytes(bytes, it) }
        } finally {
            bytes.fill(0)
        }
    }

    /** The password as UTF-8 in NFC form, so the same password typed on another keyboard still opens. */
    private fun utf8(password: CharArray): ByteArray {
        val nfc = java.text.Normalizer.normalize(java.nio.CharBuffer.wrap(password), java.text.Normalizer.Form.NFC)
        return nfc.toByteArray(Charsets.UTF_8)
    }
}
