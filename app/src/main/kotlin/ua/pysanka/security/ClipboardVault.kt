package ua.pysanka.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.system.Os
import android.system.OsConstants
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pysanka: the key for everything the clipboard keeps (its two SQLCipher databases and the image
 * and video files).
 *
 * A random 32-byte secret is stored only wrapped with an AES-256-GCM key in the phone's hardware
 * keystore (StrongBox when the phone has it). That key never leaves the hardware and, on Android 9+,
 * works only while the phone is unlocked. The keyboard has no screen of its own to ask for a
 * fingerprint, so the key itself needs no confirmation; an optional fingerprint lock for the
 * clipboard is a separate, later step.
 *
 * From the secret: the database passphrase (the secret itself) and a separate file key
 * (HMAC-SHA256 with a fixed label), so the two never share a key.
 *
 * If the hardware key is gone (the app's data was restored elsewhere, or the keystore was reset),
 * old clipboard data cannot be read: [open] reports [State.Lost] and the clipboard starts empty.
 */
object ClipboardVault {
    sealed interface State {
        /** Opened; the secret is in memory until the process ends. */
        data object Open : State
        /** The phone is locked right now (after a restart of the keyboard); try again after unlock. */
        data object Locked : State
        /** The key is gone; old data was wiped and a new key made. */
        data object Lost : State
    }

    private const val KEY_ALIAS = "pysanka_clipboard_v1"
    private const val WRAPPED_FILE = "clipboard.key"
    private const val FORMAT = 1
    private const val SECRET_BYTES = 32
    private const val GCM_TAG_BITS = 128
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private val FILE_KEY_LABEL = "pysanka/clipboard-files/v1".toByteArray()

    @Volatile
    private var secret: ByteArray? = null

    /**
     * Opens the vault (makes the key the first time). Call off the main thread.
     * [wipe] removes all clipboard data; it is called only when the old key is gone for good.
     */
    @Synchronized
    fun open(context: Context, wipe: () -> Unit): State {
        if (secret != null) return State.Open
        val dir = context.noBackupFilesDir
        val file = File(dir, WRAPPED_FILE)
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (file.exists() && ks.containsAlias(KEY_ALIAS)) {
            return try {
                secret = unwrap(ks, file)
                State.Open
            } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
                fresh(ks, file, wipe)
                State.Lost
            } catch (e: android.security.keystore.UserNotAuthenticatedException) {
                State.Locked
            } catch (e: java.security.InvalidKeyException) {
                // "Key user not authenticated" / device locked: the key is fine, the phone is locked
                State.Locked
            }
        }
        // First start, or a wrapped secret whose hardware key is gone: start over
        val lost = file.exists()
        fresh(ks, file, wipe)
        return if (lost) State.Lost else State.Open
    }

    /** The database passphrase. Only after [open] returned [State.Open]. */
    fun passphrase(): ByteArray = checkNotNull(secret) { "Clipboard vault is not open" }.copyOf()

    /** The key for clipboard files. Only after [open] returned [State.Open]. */
    fun fileKey(): SecretKey {
        val s = checkNotNull(secret) { "Clipboard vault is not open" }
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(s, "HmacSHA256")) }
        return SecretKeySpec(mac.doFinal(FILE_KEY_LABEL), "AES")
    }

    fun isOpen(): Boolean = secret != null

    private fun fresh(ks: KeyStore, file: File, wipe: () -> Unit) {
        wipe()
        if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
        val key = createKey()
        val s = ByteArray(SECRET_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        write(file, cipher.iv, cipher.doFinal(s))
        secret = s
    }

    private fun createKey(): SecretKey {
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
        } catch (e: StrongBoxUnavailableException) {
            gen.init(spec(strongBox = false))
            gen.generateKey()
        } catch (e: java.security.ProviderException) {
            // Some phones report a missing StrongBox this way
            gen.init(spec(strongBox = false))
            gen.generateKey()
        }
    }

    private fun unwrap(ks: KeyStore, file: File): ByteArray {
        val (iv, data) = DataInputStream(file.inputStream()).use { input ->
            require(input.readUnsignedByte() == FORMAT) { "Unknown clipboard key format" }
            val iv = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
            val len = input.readInt()
            require(len in 1..256) { "Damaged clipboard key" }
            iv to ByteArray(len).also { input.readFully(it) }
        }
        val key = ks.getKey(KEY_ALIAS, null) as SecretKey
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return cipher.doFinal(data).also { require(it.size == SECRET_BYTES) { "Damaged clipboard key" } }
    }

    private fun write(file: File, iv: ByteArray, data: ByteArray) {
        val tmp = File(file.parentFile, "$WRAPPED_FILE.tmp")
        FileOutputStream(tmp).use { stream ->
            DataOutputStream(stream).apply {
                writeByte(FORMAT)
                writeByte(iv.size)
                write(iv)
                writeInt(data.size)
                write(data)
                flush()
            }
            stream.fd.sync()
        }
        check(tmp.renameTo(file)) { "Could not save the clipboard key" }
        try {
            val fd = Os.open(file.parentFile!!.path, OsConstants.O_RDONLY, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
        } catch (e: Exception) {
            // Best effort; the file itself is synced
        }
    }
}
