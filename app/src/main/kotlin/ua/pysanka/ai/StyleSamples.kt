package ua.pysanka.ai

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Pysanka: «Мій стиль» — a few messages the person wrote and added themselves, so the AI can write
 * the way they do ([AiAction.MY_STYLE]). Never collected from typing.
 *
 * Kept in one file sealed with AES-256-GCM by its own key in the phone's hardware keystore
 * (StrongBox when there is one; usable only while the phone is unlocked). Its own key, not the
 * clipboard's, because the AI runs in a separate process that must read the samples without
 * opening the clipboard. A file that cannot be read (a damaged one, or a key the keystore lost)
 * gives no samples; adding new ones replaces it.
 */
object StyleSamples {
    const val MAX = 5
    const val MAX_CHARS = 600

    /** At most this much of the samples goes into one request (the model's memory is small). */
    const val PROMPT_CHARS = 2000

    private const val FILE = "style_samples.bin"
    private const val KEY_ALIAS = "pysanka_style_v1"
    private const val FORMAT = 1
    private const val GCM_TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private val AAD = "pysanka/style/v1".toByteArray()

    private fun file(context: Context) = File(context.applicationContext.noBackupFilesDir, FILE)

    /** Whether there are samples (without reading them: for showing the action). */
    fun has(context: Context): Boolean = file(context).let { it.isFile && it.length() > 0 }

    /** The samples; empty when there are none or they cannot be read. Call off the main thread. */
    @Synchronized
    fun read(context: Context): List<String> = runCatching {
        val sealed = file(context).takeIf { it.isFile }?.readBytes() ?: return emptyList()
        val input = DataInputStream(ByteArrayInputStream(sealed))
        require(input.readUnsignedByte() == FORMAT)
        val iv = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
        val data = input.readBytes()
        val key = (KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.getKey(KEY_ALIAS, null) as? SecretKey)
            ?: return emptyList()
        val plain = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            updateAAD(AAD)
        }.doFinal(data)
        try {
            DataInputStream(ByteArrayInputStream(plain)).use { s ->
                val n = s.readUnsignedByte()
                require(n <= MAX)
                List(n) { cut(s.readUTF()) }.filter { it.isNotBlank() }
            }
        } finally {
            plain.fill(0)
        }
    }.getOrDefault(emptyList())

    /** Keeps [samples] (at most [MAX], each at most [MAX_CHARS]); none removes the file. Off the main thread. */
    @Synchronized
    fun write(context: Context, samples: List<String>) {
        val kept = samples.map { cut(it.trim()) }.filter { it.isNotEmpty() }.take(MAX)
        val target = file(context)
        if (kept.isEmpty()) {
            target.delete()
            return
        }
        val plain = ByteArrayOutputStream().also { b ->
            DataOutputStream(b).use { out ->
                out.writeByte(kept.size)
                kept.forEach { out.writeUTF(it) }
            }
        }.toByteArray()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key())
            updateAAD(AAD)
        }
        val sealed = try { cipher.doFinal(plain) } finally { plain.fill(0) }
        val tmp = File(target.parentFile, "$FILE.tmp")
        FileOutputStream(tmp).use { stream ->
            DataOutputStream(stream).apply {
                writeByte(FORMAT)
                writeByte(cipher.iv.size)
                write(cipher.iv)
                write(sealed)
                flush()
            }
            stream.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            error("Could not save the style samples")
        }
    }

    /** The samples as they go to the model: in order, those that fit in [PROMPT_CHARS] together (the first always). */
    fun forPrompt(samples: List<String>): List<String> {
        var left = PROMPT_CHARS
        return samples.filterIndexed { i, s -> (i == 0 || s.length <= left).also { if (it) left -= s.length } }
    }

    /** At most [MAX_CHARS], never cutting an emoji or another character in two. */
    fun cut(text: String): String {
        if (text.length <= MAX_CHARS) return text
        val end = if (Character.isHighSurrogate(text[MAX_CHARS - 1])) MAX_CHARS - 1 else MAX_CHARS
        return text.substring(0, end)
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
            KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        ).apply {
            setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            setKeySize(256)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                setUnlockedDeviceRequired(true)
                if (strongBox) setIsStrongBoxBacked(true)
            }
        }.build()
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        return try {
            gen.init(spec(strongBox = true))
            gen.generateKey()
        } catch (e: java.security.ProviderException) {
            // No StrongBox on this phone
            gen.init(spec(strongBox = false))
            gen.generateKey()
        }
    }
}
