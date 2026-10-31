/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.clipboard.provider

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import dev.patrickgold.florisboard.lib.devtools.LogTopic
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import org.florisboard.lib.kotlin.io.FsFile
import org.florisboard.lib.kotlin.io.subFile
import ua.pysanka.clipboard.SecureClipboard
import ua.pysanka.security.ClipboardVault
import ua.pysanka.security.SealedFile
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.UUID

/**
 * Backend helper object which is used by [ClipboardMediaProvider] to serve content.
 */
object ClipboardFileStorage {
    const val CLIPBOARD_FILES_PATH = "clipboard_files"
    const val OPEN_DIR = "clipboard_open"

    /** Pysanka: largest image kept in the history (screenshots are a few MB). */
    const val MAX_IMAGE_BYTES = 20L * 1024 * 1024

    private val Context.clipboardFilesDir: FsFile
        get() = FsFile(this.noBackupFilesDir, "clipboard_files").also { it.mkdirs() }

    /** Pysanka: a stored file's id and its original (not encrypted) size. */
    data class Cloned(val id: Long, val size: Long)

    /**
     * Clones a content URI to internal storage, encrypted (Pysanka).
     *
     * @param uri The URI
     *
     * @return The file's name, which is a unique long, and its original size
     */
    @Synchronized
    fun cloneUri(context: Context, uri: Uri): Cloned {
        check(SecureClipboard.ensureOpen(context)) { "Clipboard is locked" }
        val id = System.nanoTime()
        val file = context.clipboardFilesDir.subFile(id.toString())
        var size = 0L
        try {
            context.contentResolver.openInputStream(uri)!!.use { raw ->
                val counting = object : FilterInputStream(raw) {
                    override fun read(): Int = super.read().also { if (it >= 0) count(1) }
                    override fun read(b: ByteArray, off: Int, len: Int): Int =
                        super.read(b, off, len).also { if (it > 0) count(it.toLong()) }
                    private fun count(n: Long) {
                        size += n
                        if (size > MAX_IMAGE_BYTES) throw java.io.IOException("Too large for the clipboard history")
                    }
                }
                file.outputStream().use { out -> SealedFile.seal(ClipboardVault.fileKey(), label(id), counting, out) }
            }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        return Cloned(id, size)
    }

    /** Pysanka: each file is sealed to its own id, so two stored files cannot be swapped unnoticed. */
    private fun label(id: Long): ByteArray = java.nio.ByteBuffer.allocate(8).putLong(id).array()

    /** Pysanka: the original bytes of a stored file. */
    fun openStream(context: Context, id: Long): InputStream {
        check(SecureClipboard.ensureOpen(context)) { "Clipboard is locked" }
        return SealedFile.open(ClipboardVault.fileKey(), label(id), getFileForId(context, id).inputStream().buffered())
    }

    /**
     * Pysanka: a readable, seekable descriptor with the original bytes, for apps that receive a
     * pasted image or video and for video thumbnails. The file is decrypted into the app's private
     * cache and unlinked right after it is opened, so it disappears when the descriptor is closed.
     */
    fun openPlainDescriptor(context: Context, id: Long): ParcelFileDescriptor {
        val dir = File(context.noBackupFilesDir, OPEN_DIR).apply { mkdirs() }
        // Leftovers from a crash in the middle of this function
        dir.listFiles()?.forEach { if (it.lastModified() < System.currentTimeMillis() - 60_000) it.delete() }
        val tmp = File(dir, UUID.randomUUID().toString())
        try {
            openStream(context, id).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            return ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
        } finally {
            tmp.delete()
        }
    }

    /**
     * Deletes the file corresponding to an id.
     */
    fun deleteById(context: Context, id: Long) {
        flogDebug(LogTopic.CLIPBOARD) { "Cleaning up $id" }
        val file = context.clipboardFilesDir.subFile(id.toString())
        file.delete()
    }

    fun getFileForId(context: Context, id: Long): FsFile {
        return context.clipboardFilesDir.subFile(id.toString())
    }


    /**
     * Insert file from backup if not existing
     *
     * @param context the application context
     * @param file the file to be inserted
     */
    fun insertFileFromBackupIfNotExisting(context: Context, file: FsFile) {
        if (!context.clipboardFilesDir.subFile(file.name).isFile) {
            file.copyTo(context.clipboardFilesDir.subFile(file.name), overwrite = false)
        }
    }

    /**
     * Deletes all files from the clipboard subdirectory
     *
     * @param context the application context
     */
    fun resetClipboardFileStorage(context: Context) {
        context.clipboardFilesDir.listFiles()?.forEach {
            it.delete()
        }
    }

}
