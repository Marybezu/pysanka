package ua.pysanka.security

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import androidx.core.os.UserManagerCompat
import android.system.Os
import android.system.OsConstants
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
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
 * If the hardware key is really gone (the keystore was reset), old clipboard data cannot be read:
 * after several attempts [open] reports [State.Lost] and the clipboard starts empty.
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
    private const val MISSES_FILE = "clipboard.key.misses"
    private const val LOST_AFTER = 3
    private const val MISS_SPACING_MS = 10 * 60 * 1000L
    private val HEX = "0123456789abcdef".toCharArray()
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
     *
     * Data is never wiped on a single failure: a keystore that is busy or not reachable for a moment
     * looks like a missing key on some phones. Only when the key is missing or unusable on
     * [LOST_AFTER] attempts at least ten minutes apart, while the phone is unlocked, is it treated as lost.
     */
    @Synchronized
    fun open(context: Context, wipe: () -> Unit): State {
        if (secret != null) return State.Open
        // Before the first unlock the key file is in storage that cannot be read yet
        if (!UserManagerCompat.isUserUnlocked(context)) return State.Locked
        val dir = context.noBackupFilesDir
        val file = File(dir, WRAPPED_FILE)
        val ks = try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        } catch (e: Exception) {
            return State.Locked
        }
        if (!file.exists()) {
            // First start (or the app stopped before the first key was saved): nothing encrypted
            // with a key of ours can exist, so starting fresh loses nothing
            fresh(ks, file, wipe)
            return State.Open
        }
        val key = try {
            ks.getKey(KEY_ALIAS, null) as? SecretKey
        } catch (e: Exception) {
            return State.Locked
        } ?: return missOrLost(context, ks, file, wipe)
        return try {
            secret = unwrap(key, file)
            File(dir, MISSES_FILE).delete()
            State.Open
        } catch (e: KeyPermanentlyInvalidatedException) {
            missOrLost(context, ks, file, wipe)
        } catch (e: UserNotAuthenticatedException) {
            State.Locked
        } catch (e: InvalidKeyException) {
            // Usually the phone is locked (the key works only while it is unlocked); if it is not,
            // this counts as a failed attempt (missOrLost checks the lock itself)
            missOrLost(context, ks, file, wipe)
        } catch (e: AEADBadTagException) {
            missOrLost(context, ks, file, wipe)
        } catch (e: IllegalArgumentException) {
            missOrLost(context, ks, file, wipe)
        } catch (e: java.io.IOException) {
            missOrLost(context, ks, file, wipe)
        }
    }

    /** One more failed attempt; after [LOST_AFTER] of them, while unlocked, the key counts as lost. */
    private fun missOrLost(context: Context, ks: KeyStore, file: File, wipe: () -> Unit): State {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isDeviceLocked == true) return State.Locked
        val misses = File(context.noBackupFilesDir, MISSES_FILE)
        // "count last-time": attempts are counted at most once per MISS_SPACING_MS, so a burst of
        // calls in the same moment (the clipboard and its file list open together) counts once
        val (prev, last) = runCatching {
            misses.readText().trim().split(' ').let { it[0].toInt() to it[1].toLong() }
        }.getOrNull() ?: (0 to 0L)
        val now = System.currentTimeMillis()
        if (prev > 0 && now - last in 0 until MISS_SPACING_MS) return State.Locked
        val count = prev + 1
        if (count < LOST_AFTER) {
            runCatching { misses.writeText("$count $now") }
            return State.Locked
        }
        misses.delete()
        fresh(ks, file, wipe)
        return State.Lost
    }

    /**
     * The database passphrase, as a raw SQLCipher key (x'…'). The secret is already 32 random bytes,
     * so SQLCipher's slow password stretching would add nothing but a delay on every open.
     * Only after [open] returned [State.Open].
     */
    fun passphrase(): ByteArray {
        val s = checkNotNull(secret) { "Clipboard vault is not open" }
        val hex = CharArray(s.size * 2)
        for (i in s.indices) {
            val v = s[i].toInt() and 0xFF
            hex[i * 2] = HEX[v ushr 4]
            hex[i * 2 + 1] = HEX[v and 0x0F]
        }
        val out = ByteArray(hex.size + 3)
        out[0] = 'x'.code.toByte(); out[1] = '\''.code.toByte()
        for (i in hex.indices) out[i + 2] = hex[i].code.toByte()
        out[out.size - 1] = '\''.code.toByte()
        hex.fill('0')
        return out
    }

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
        } catch (e: java.security.ProviderException) {
            // No StrongBox (StrongBoxUnavailableException is a ProviderException; some phones throw the base class)
            gen.init(spec(strongBox = false))
            gen.generateKey()
        }
    }

    private fun unwrap(key: SecretKey, file: File): ByteArray {
        val (iv, data) = DataInputStream(file.inputStream()).use { input ->
            require(input.readUnsignedByte() == FORMAT) { "Unknown clipboard key format" }
            val iv = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
            val len = input.readInt()
            require(len in 1..256) { "Damaged clipboard key" }
            iv to ByteArray(len).also { input.readFully(it) }
        }
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
