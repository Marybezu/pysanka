package ua.pysanka.typing

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ua.pysanka.clipboard.SecureClipboard
import ua.pysanka.security.ClipboardVault
import ua.pysanka.security.SealedFile
import java.io.File
import java.io.FileOutputStream

/**
 * Pysanka: the learned words ([PersonalWords]), one encrypted file per language in the app's
 * private no-backup folder, sealed with the vault's words key (AES-256-GCM, see SealedFile).
 *
 * Nothing is read or learned while the vault is closed (before the first unlock after a restart);
 * the callers make sure nothing comes from a password, incognito or "no learning" field. Changes
 * are written a few seconds after the last one and when a field is left.
 */
object UserWords {
    const val DIR = "user_words"

    private const val SAVE_DELAY_MS = 3_000L
    private const val VAULT_RETRY_MS = 30_000L
    private const val MAX_FILE_BYTES = 4 * 1024 * 1024
    private val LANGUAGE = Regex("[a-z]{2,8}")

    /** One worker, so learning and writing happen in order. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + worker)

    private val lock = Any()
    /** Read without the lock by [peek], so the main thread never waits for a file. */
    private val loaded = java.util.concurrent.ConcurrentHashMap<String, PersonalWords>()
    private val dirty = HashSet<String>()
    @Volatile
    private var lastVaultTry = 0L
    private var saveJob: Job? = null

    private fun day() = (System.currentTimeMillis() / 86_400_000L).toInt()

    /**
     * The words for [language], or null while the vault is closed. The first time it reads the
     * file, so call it off the main thread.
     */
    fun get(context: Context, language: String): PersonalWords? {
        if (!LANGUAGE.matches(language)) return null
        synchronized(lock) { loaded[language]?.let { return it } }
        // Outside the lock: opening the vault may wipe (and so lock) on its own
        if (!vaultOpen(context)) return null
        synchronized(lock) {
            loaded[language]?.let { return it }
            return load(context, language).also { loaded[language] = it }
        }
    }

    /** The words already in memory (never reads a file; for the main thread). */
    fun peek(language: String): PersonalWords? = loaded[language]

    /** One use of [word], after [previous] in the same sentence if given; see [PersonalWords.learn]. */
    fun learn(context: Context, language: String, word: String, insisted: Boolean, midSentence: Boolean, previous: String?) {
        if (!PersonalWords.isLearnable(word)) return
        val app = context.applicationContext
        scope.launch {
            val words = get(app, language) ?: return@launch
            val day = day()
            val learned = words.learn(word, insisted, midSentence, day)
            val paired = previous != null && words.learnPair(previous, word, day)
            if (learned || paired) changed(app, language)
        }
    }

    /** Takes back one use of an auto-correction the person undid (and of its pair). */
    fun unlearn(context: Context, language: String, word: String, previous: String?) {
        val app = context.applicationContext
        scope.launch {
            val words = get(app, language) ?: return@launch
            val unlearned = words.unlearn(word)
            val unpaired = previous != null && words.unlearnPair(previous, word)
            if (unlearned || unpaired) changed(app, language)
        }
    }

    /** Forgets that [word] follows [previous] (long press on an offered next word). */
    fun forgetPair(context: Context, language: String, previous: String, word: String): Boolean {
        val words = peek(language) ?: return false
        return words.forgetPair(previous, word).also { if (it) changed(context.applicationContext, language) }
    }

    /** Forgets one word (long press on its suggestion). */
    fun forget(context: Context, language: String, word: String): Boolean {
        val words = peek(language) ?: return false
        return words.forget(word).also { if (it) changed(context.applicationContext, language) }
    }

    /** Writes what changed now (a field was left). */
    fun flush(context: Context) {
        val app = context.applicationContext
        scope.launch { save(app) }
    }

    /** Forgets every learned word, in every language. */
    fun clearAll(context: Context) {
        val app = context.applicationContext
        scope.launch { wipe(app) }
    }

    /** Removes the words from memory and the phone (also when the vault's key is lost). */
    fun wipe(context: Context) {
        synchronized(lock) {
            loaded.clear()
            dirty.clear()
            File(context.noBackupFilesDir, DIR).listFiles()?.forEach { it.delete() }
        }
    }

    private fun vaultOpen(context: Context): Boolean {
        if (ClipboardVault.isOpen()) return true
        // A locked phone: not asked again on every key
        val now = SystemClock.elapsedRealtime()
        if (lastVaultTry != 0L && now - lastVaultTry < VAULT_RETRY_MS) return false
        lastVaultTry = now
        return SecureClipboard.ensureOpen(context) && ClipboardVault.isOpen()
    }

    private fun file(context: Context, language: String) = File(File(context.noBackupFilesDir, DIR), "$language.words")

    private fun label(language: String) = "user-words/$language".toByteArray()

    private fun load(context: Context, language: String): PersonalWords {
        val file = file(context, language)
        if (!file.isFile) return PersonalWords()
        // A damaged file (or one from a lost key) is started over; the next write replaces it
        return runCatching {
            check(file.length() <= MAX_FILE_BYTES)
            val bytes = file.inputStream().use { input ->
                SealedFile.open(ClipboardVault.wordsKey(), label(language), input).use { it.readBytes() }
            }
            try { PersonalWords.decode(bytes) } finally { bytes.fill(0) }
        }.getOrElse { PersonalWords() }
    }

    private fun changed(context: Context, language: String) {
        synchronized(lock) { dirty.add(language) }
        scope.launch {
            saveJob?.cancel()
            saveJob = scope.launch {
                delay(SAVE_DELAY_MS)
                save(context)
            }
        }
    }

    /** Under the lock, so a wipe never races a write. */
    private fun save(context: Context) = synchronized(lock) {
        if (!ClipboardVault.isOpen()) return
        val toWrite = dirty.mapNotNull { lang -> loaded[lang]?.let { lang to it.encode() } }
        dirty.clear()
        for ((language, bytes) in toWrite) {
            runCatching {
                val target = file(context, language)
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, "$language.tmp")
                FileOutputStream(tmp).use { file ->
                    // The writer closes the file too; a second close does nothing
                    SealedFile.writer(ClipboardVault.wordsKey(), label(language), file).use { it.write(bytes) }
                }
                // On the disk before it replaces the old file (a power cut leaves one of the two whole)
                FileOutputStream(tmp, true).use { it.fd.sync() }
                // Whole or not at all: a write cut short never replaces the old file
                if (!tmp.renameTo(target)) tmp.delete()
            }
            bytes.fill(0)
        }
    }
}
