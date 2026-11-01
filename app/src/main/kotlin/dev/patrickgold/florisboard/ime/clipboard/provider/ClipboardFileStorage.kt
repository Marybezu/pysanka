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

    /** Pysanka: largest image kept in the history (screenshots are a few MB). */
    const val MAX_IMAGE_BYTES = 20L * 1024 * 1024

    private val Context.clipboardFilesDir: FsFile
        get() = FsFile(this.noBackupFilesDir, ua.pysanka.clipboard.SecureClipboard.FILES_DIR).also { it.mkdirs() }

    /** Pysanka: a stored file's id and its original (not encrypted) size. */
    data class Cloned(val id: Long, val size: Long)

    /**
     * Clones a content URI to internal storage, encrypted (Pysanka).
     *
     * @param uri The URI
     *
     * @return The file's name, which is a unique long, and its original size
     */
    fun cloneUri(context: Context, uri: Uri): Cloned =
        context.contentResolver.openInputStream(uri)!!.use { storeStream(context, it) }

    private val lastId = java.util.concurrent.atomic.AtomicLong()

    /** A unique file id, also when two files are stored at the same moment on different threads. */
    private fun newId(): Long {
        val now = System.nanoTime()
        return lastId.updateAndGet { prev -> if (now > prev) now else prev + 1 }
    }

    /**
     * Pysanka: stores (encrypted) whatever [input] holds, up to [MAX_IMAGE_BYTES]; also used by
     * import. Not synchronized: a slow read (a large image from a cloud file) must not hold up copies.
     */
    fun storeStream(context: Context, input: InputStream): Cloned {
        check(SecureClipboard.ensureOpen(context)) { "Clipboard is locked" }
        val id = newId()
        val file = context.clipboardFilesDir.subFile(id.toString())
        var size = 0L
        try {
            val counting = object : FilterInputStream(input) {
                override fun read(): Int = super.read().also { if (it >= 0) count(1) }
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, len).also { if (it > 0) count(it.toLong()) }
                private fun count(n: Long) {
                    size += n
                    if (size > MAX_IMAGE_BYTES) throw java.io.IOException("Too large for the clipboard history")
                }
            }
            file.outputStream().use { out -> SealedFile.seal(ClipboardVault.fileKey(), label(id), counting, out) }
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
        // Pysanka: on Android 11+ the decrypted copy lives only in memory (an anonymous memory file)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            runCatching { return memoryCopy(context, id) }
        }
        val dir = File(context.noBackupFilesDir, ua.pysanka.clipboard.SecureClipboard.OPEN_DIR).apply { mkdirs() }
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

    @androidx.annotation.RequiresApi(30)
    private fun memoryCopy(context: Context, id: Long): ParcelFileDescriptor {
        val fd = android.system.Os.memfd_create("clip", 0)
        try {
            java.io.FileOutputStream(fd).let { out -> openStream(context, id).use { it.copyTo(out) } }
            android.system.Os.lseek(fd, 0, android.system.OsConstants.SEEK_SET)
            return ParcelFileDescriptor.dup(fd)
        } finally {
            android.system.Os.close(fd)
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




}
