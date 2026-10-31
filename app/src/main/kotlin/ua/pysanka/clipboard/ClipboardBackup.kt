package ua.pysanka.clipboard

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardFileInfo
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardFileStorage
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardMediaProvider
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.json.JSONArray
import org.json.JSONObject
import ua.pysanka.security.SealedFile
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.spec.SecretKeySpec

/**
 * Pysanka: a copy of the clipboard history (text and images) that can be taken to another phone,
 * and import of the clipboard from a FlorisBoard backup.
 *
 * A Pysanka copy is always protected with a password: the history can hold passwords and codes.
 * File: 16-byte salt, then a [SealedFile] stream (AES-256-GCM in chunks) keyed by Argon2id of the
 * password (64 MiB, 3 passes), holding a ZIP with `items.json` and `images/<n>`. Nothing in
 * plain text is ever written to the phone's storage on the way in or out.
 *
 * A FlorisBoard backup is a plain ZIP (clipboard/clipboard_text_items.json,
 * clipboard/clipboard_images.json, clipboard/clipboard_files/<id>); only its clipboard is read.
 */
object ClipboardBackup {
    const val MIN_PASSWORD_LENGTH = 12
    const val FILE_EXTENSION = "pysanka"

    private const val SALT_BYTES = 16
    private val LABEL = "pysanka/clipboard-copy/v1".toByteArray()
    private const val ITEMS = "items.json"
    private const val IMAGES = "images/"
    private const val FORMAT = 1

    /** Argon2id settings; a few seconds on a phone, very costly to guess at scale. */
    private const val ARGON_MEMORY_KIB = 64 * 1024
    private const val ARGON_ITERATIONS = 3

    /** Most items and bytes accepted from a file, so a damaged or hostile one cannot fill the phone. */
    private const val MAX_ITEMS = 5_000
    private const val MAX_JSON_BYTES = 32L * 1024 * 1024

    enum class Kind { PYSANKA, FLORISBOARD, UNKNOWN }

    data class Options(val text: Boolean, val images: Boolean, val pinnedOnly: Boolean)

    data class ImportResult(val added: Int, val skipped: Int)

    class WrongPasswordOrDamaged : Exception()

    /** What kind of file [uri] is: a FlorisBoard backup is a ZIP; anything else is tried as a Pysanka copy. */
    fun detect(context: Context, uri: Uri): Kind = runCatching {
        context.contentResolver.openInputStream(uri)!!.use { input ->
            val head = ByteArray(4)
            val n = SealedFile.readFully(input, head)
            when {
                n == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
                    head[2].toInt() == 3 && head[3].toInt() == 4 -> Kind.FLORISBOARD
                n == 4 -> Kind.PYSANKA
                else -> Kind.UNKNOWN
            }
        }
    }.getOrDefault(Kind.UNKNOWN)

    // ---------------------------------------------------------------- export

    /** Writes a password-protected copy of the history to [uri]. Returns how many items went in. */
    suspend fun export(context: Context, uri: Uri, password: CharArray, options: Options): Int = withContext(Dispatchers.IO) {
        require(password.size >= MIN_PASSWORD_LENGTH)
        val clipboardManager by context.clipboardManager()
        val items = clipboardManager.currentHistory.all.filter { item ->
            (!options.pinnedOnly || item.isPinned) && when (item.type) {
                ItemType.TEXT -> options.text && item.text != null
                ItemType.IMAGE -> options.images && item.uri != null
                ItemType.VIDEO -> false
            }
        }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)
        var count = 0
        try {
            context.contentResolver.openOutputStream(uri, "wt")!!.use { raw ->
                raw.write(salt)
                ZipOutputStream(SealedFile.writer(SecretKeySpec(key, "AES"), LABEL, raw)).use { zip ->
                    val list = JSONArray()
                    var n = 0
                    for (item in items) {
                        ensureActive()
                        val o = JSONObject()
                            .put("pinned", item.isPinned)
                            .put("time", item.creationTimestampMs)
                            .put("sensitive", item.isSensitive)
                            .put("mime", JSONArray(item.mimeTypes))
                        if (item.type == ItemType.TEXT) {
                            o.put("type", "text").put("text", item.text)
                        } else {
                            val name = IMAGES + (n++)
                            val ok = runCatching {
                                ClipboardFileStorage.openStream(context, ContentUris.parseId(item.uri!!)).use { input ->
                                    zip.putNextEntry(ZipEntry(name))
                                    input.copyTo(zip)
                                    zip.closeEntry()
                                }
                            }.isSuccess
                            if (!ok) continue
                            o.put("type", "image").put("file", name)
                        }
                        list.put(o)
                        count++
                    }
                    zip.putNextEntry(ZipEntry(ITEMS))
                    zip.write(JSONObject().put("format", FORMAT).put("items", list).toString().toByteArray())
                    zip.closeEntry()
                }
            }
        } finally {
            key.fill(0)
        }
        count
    }

    // ---------------------------------------------------------------- import

    /** Imports a Pysanka copy (needs [password]) or a FlorisBoard backup (no password). */
    suspend fun import(context: Context, uri: Uri, password: CharArray?): ImportResult = withContext(Dispatchers.IO) {
        when (detect(context, uri)) {
            Kind.FLORISBOARD -> context.contentResolver.openInputStream(uri)!!.use { importFloris(context, it) }
            Kind.PYSANKA -> {
                requireNotNull(password)
                context.contentResolver.openInputStream(uri)!!.use { importPysanka(context, it, password) }
            }
            Kind.UNKNOWN -> throw IOException("Not a clipboard copy")
        }
    }

    private suspend fun importPysanka(context: Context, input: InputStream, password: CharArray): ImportResult {
        val salt = ByteArray(SALT_BYTES)
        if (SealedFile.readFully(input, salt) != SALT_BYTES) throw WrongPasswordOrDamaged()
        val key = deriveKey(password, salt)
        val stored = HashMap<String, ClipboardFileStorage.Cloned>()
        try {
            var json: JSONObject? = null
            try {
                ZipInputStream(SealedFile.open(SecretKeySpec(key, "AES"), LABEL, BufferedInputStream(input))).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        when {
                            entry.name == ITEMS -> json = JSONObject(readLimited(zip, MAX_JSON_BYTES))
                            entry.name.startsWith(IMAGES) && stored.size < MAX_ITEMS ->
                                runCatching { ClipboardFileStorage.storeStream(context, zip) }.getOrNull()?.let { stored[entry.name] = it }
                        }
                    }
                }
            } catch (e: IOException) {
                // A wrong password and a damaged file look the same, on purpose
                throw WrongPasswordOrDamaged()
            }
            val list = json?.optJSONArray("items") ?: throw WrongPasswordOrDamaged()
            val items = ArrayList<ClipboardItem>()
            val used = HashSet<String>()
            for (i in 0 until minOf(list.length(), MAX_ITEMS)) {
                val o = list.optJSONObject(i) ?: continue
                val mime = o.optJSONArray("mime")?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList()
                val time = o.optLong("time", System.currentTimeMillis())
                val pinned = o.optBoolean("pinned")
                val sensitive = o.optBoolean("sensitive")
                when (o.optString("type")) {
                    "text" -> o.optString("text").takeIf { o.has("text") }?.let {
                        items.add(textItem(it, time, pinned, sensitive))
                    }
                    "image" -> {
                        val name = o.optString("file")
                        val file = stored[name] ?: continue
                        used.add(name)
                        items.add(imageItem(context, file, name, mime, time, pinned, sensitive))
                    }
                }
            }
            // Images with no item in the list are not kept
            stored.filterKeys { it !in used }.values.forEach { ClipboardFileStorage.deleteById(context, it.id) }
            return add(context, items)
        } catch (e: Exception) {
            stored.values.forEach { ClipboardFileStorage.deleteById(context, it.id) }
            throw e
        } finally {
            key.fill(0)
        }
    }

    private suspend fun importFloris(context: Context, input: InputStream): ImportResult {
        val stored = HashMap<String, ClipboardFileStorage.Cloned>() // old id -> stored copy
        try {
            var texts: JSONArray? = null
            var images: JSONArray? = null
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.removePrefix("/")
                    when {
                        name.endsWith("clipboard/clipboard_text_items.json") -> texts = JSONArray(readLimited(zip, MAX_JSON_BYTES))
                        name.endsWith("clipboard/clipboard_images.json") -> images = JSONArray(readLimited(zip, MAX_JSON_BYTES))
                        name.contains("clipboard/clipboard_files/") && !entry.isDirectory && stored.size < MAX_ITEMS ->
                            runCatching { ClipboardFileStorage.storeStream(context, zip) }.getOrNull()
                                ?.let { stored[name.substringAfterLast('/')] = it }
                    }
                }
            }
            if (texts == null && images == null) throw IOException("No clipboard in this backup")
            val items = ArrayList<ClipboardItem>()
            val used = HashSet<String>()
            texts?.let { a ->
                for (i in 0 until minOf(a.length(), MAX_ITEMS)) {
                    val o = a.optJSONObject(i) ?: continue
                    if (florisType(o) != ItemType.TEXT) continue
                    val text = o.optString("text").takeIf { o.has("text") && !o.isNull("text") } ?: continue
                    items.add(textItem(text, o.optLong("creationTimestampMs", System.currentTimeMillis()), o.optBoolean("isPinned"), o.optBoolean("isSensitive")))
                }
            }
            images?.let { a ->
                for (i in 0 until minOf(a.length(), MAX_ITEMS)) {
                    val o = a.optJSONObject(i) ?: continue
                    if (florisType(o) != ItemType.IMAGE) continue
                    val oldId = o.optString("uri").substringAfterLast('/')
                    val file = stored[oldId] ?: continue
                    used.add(oldId)
                    val mime = o.optJSONArray("mimeTypes")?.let { m -> List(m.length()) { m.optString(it) } } ?: listOf("image/*")
                    items.add(
                        imageItem(context, file, "Image", mime, o.optLong("creationTimestampMs", System.currentTimeMillis()),
                            o.optBoolean("isPinned"), o.optBoolean("isSensitive")),
                    )
                }
            }
            stored.filterKeys { it !in used }.values.forEach { ClipboardFileStorage.deleteById(context, it.id) }
            return add(context, items)
        } catch (e: Exception) {
            stored.values.forEach { ClipboardFileStorage.deleteById(context, it.id) }
            throw e
        }
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

    private fun imageItem(
        context: Context, file: ClipboardFileStorage.Cloned, displayName: String, mime: List<String>,
        time: Long, pinned: Boolean, sensitive: Boolean,
    ): ClipboardItem {
        val mimeTypes = mime.filter { it.startsWith("image/") }.ifEmpty { listOf("image/png") }
        val uri = provider(context).registerImported(ClipboardFileInfo(file.id, displayName, file.size, 0, mimeTypes))
        return ClipboardItem(
            type = ItemType.IMAGE, text = null, uri = uri, creationTimestampMs = time, isPinned = pinned,
            mimeTypes = mimeTypes, isSensitive = sensitive,
        )
    }

    private fun provider(context: Context): ClipboardMediaProvider {
        val client = checkNotNull(context.contentResolver.acquireContentProviderClient(ClipboardMediaProvider.AUTHORITY))
        try {
            return client.localContentProvider as ClipboardMediaProvider
        } finally {
            client.close()
        }
    }

    private suspend fun add(context: Context, items: List<ClipboardItem>): ImportResult {
        val clipboardManager by context.clipboardManager()
        val (added, skipped) = clipboardManager.importItems(items.sortedBy { it.creationTimestampMs })
        return ImportResult(added, skipped)
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
