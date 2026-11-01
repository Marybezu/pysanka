package ua.pysanka.ai

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pysanka: where the AI model file lives and how it gets there. Pysanka has no internet
 * permission: the person downloads the model in their browser and picks the file here; it is
 * copied into Pysanka's private storage and accepted only if it is exactly the published file
 * (checked by SHA-256 while copying). The same file as in Tarilka and Mandat.
 */
class ModelStore(private val context: Context) {

    sealed interface State {
        data object Missing : State
        data class Ready(val sizeMb: Long) : State
    }

    class WrongFile : Exception()
    class NoSpace : Exception()

    /** App-private storage: no other app can read or replace it, and it needs no permission. */
    private val dir: File get() = File(context.filesDir, MODEL_DIR).apply { mkdirs() }

    fun modelFile(): File? = File(dir, MODEL_NAME).takeIf { it.isFile && it.length() == E2B_BYTES }

    fun state(): State {
        val f = modelFile() ?: return State.Missing
        return State.Ready(f.length() / 1_000_000) // decimal MB, like the size on the download page
    }

    data class ImportResult(val originalDeleted: Boolean?)

    /**
     * Copies a model the person picked with the system file picker. When [deleteOriginal] is set,
     * tries to remove the picked file afterwards (null = not asked, false = the storage did not allow it).
     */
    suspend fun import(uri: Uri, deleteOriginal: Boolean, onProgress: (Float) -> Unit): ImportResult =
        withContext(Dispatchers.IO) {
            val cr = context.contentResolver
            val total = runCatching { cr.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
            val tmp = File(dir, "import.tmp")
            tmp.delete() // a leftover from a failed copy must not block the space check
            // A file of another size is refused at once, not after copying gigabytes
            if (total > 0 && total != E2B_BYTES) throw WrongFile()
            if (dir.usableSpace < E2B_BYTES + 200 * MB) throw NoSpace()
            val sha = java.security.MessageDigest.getInstance("SHA-256")
            try {
                cr.openInputStream(uri)!!.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 20)
                        var copied = 0L
                        while (true) {
                            // Stops when the screen that started it is gone; the temp file is removed below
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            sha.update(buf, 0, n)
                            copied += n
                            // A wrong, huge file must not fill the phone before the checksum says no
                            if (copied > E2B_BYTES) throw WrongFile()
                            onProgress(copied.toFloat() / (if (total > 0) total else E2B_BYTES))
                        }
                    }
                }
                val hex = sha.digest().joinToString("") { "%02x".format(it) }
                if (tmp.length() != E2B_BYTES || hex != E2B_SHA256) throw WrongFile()
            } catch (t: Throwable) {
                tmp.delete()
                throw t
            }
            val target = File(dir, MODEL_NAME)
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            val deleted = if (deleteOriginal) {
                runCatching { DocumentsContract.deleteDocument(cr, uri) }.getOrDefault(false)
            } else {
                null
            }
            ImportResult(deleted)
        }

    fun delete() {
        dir.listFiles()?.forEach { it.delete() }
        AiEngine.cacheDir(context).deleteRecursively()
        AiEntry.sync(context)
    }

    /**
     * Removes what an interrupted copy left (a temporary or partial file, up to 2.6 GB). Called when
     * the AI settings open, never while a copy runs.
     */
    fun cleanUp() {
        dir.listFiles()?.forEach { f -> if (f.name != MODEL_NAME || f.length() != E2B_BYTES) f.delete() }
    }

    companion object {
        const val E2B_PAGE = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm"
        const val E2B_FILE = "gemma-4-E2B-it.litertlm"
        /** A fixed revision of the model page, so the download is always the file with [E2B_SHA256]. */
        private const val E2B_REVISION = "1caa527c1e88b55f046fcd8d2b4265606680663d"
        const val E2B_DOWNLOAD = "$E2B_PAGE/resolve/$E2B_REVISION/$E2B_FILE?download=true"
        /** SHA-256 of that file as published (Hugging Face LFS), 2 588 147 712 bytes (same as in Tarilka). */
        const val E2B_BYTES = 2_588_147_712L
        private const val E2B_SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
        private const val MODEL_DIR = "ai_model"
        private const val MODEL_NAME = "model.litertlm"
        private const val MB = 1024L * 1024L
    }
}
