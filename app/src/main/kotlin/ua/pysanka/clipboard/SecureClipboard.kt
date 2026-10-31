package ua.pysanka.clipboard

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import ua.pysanka.security.ClipboardVault
import java.io.File

/**
 * Pysanka: one place that opens the encrypted clipboard storage.
 *
 * Both clipboard databases (history and file info) use SQLCipher with the passphrase from
 * [ClipboardVault]; the image and video files are sealed with its file key (see SealedFile).
 * Nothing is opened before the vault is: before the first unlock after a restart, and while the
 * phone is locked, the clipboard simply stays closed.
 */
object SecureClipboard {
    const val HISTORY_DB = "clipboard_history"
    const val FILES_DB = "clipboard_files"
    const val FILES_DIR = "clipboard_files"
    const val OPEN_DIR = "clipboard_open"

    /** Opens the vault; on a lost key every old clipboard file is wiped first. Call off the main thread. */
    fun open(context: Context): ClipboardVault.State {
        val app = context.applicationContext
        val state = ClipboardVault.open(app) { wipe(app) }
        if (state == ClipboardVault.State.Open || state == ClipboardVault.State.Lost) {
            // Decrypted copies left from a crash while one was handed out
            File(app.noBackupFilesDir, OPEN_DIR).listFiles()?.forEach { it.delete() }
        }
        return state
    }

    /** True when the clipboard can be read and written right now. */
    fun ensureOpen(context: Context): Boolean =
        ClipboardVault.isOpen() || runCatching { open(context) }.getOrNull().let { it == ClipboardVault.State.Open || it == ClipboardVault.State.Lost }

    /** SQLCipher for a clipboard database. Only when the vault is open. */
    fun openHelperFactory(): SupportSQLiteOpenHelper.Factory {
        System.loadLibrary("sqlcipher")
        return SupportOpenHelperFactory(ClipboardVault.passphrase(), Hardening, false)
    }

    private fun wipe(context: Context) {
        context.deleteDatabase(HISTORY_DB)
        context.deleteDatabase(FILES_DB)
        File(context.noBackupFilesDir, FILES_DIR).listFiles()?.forEach { it.delete() }
        File(context.noBackupFilesDir, OPEN_DIR).listFiles()?.forEach { it.delete() }
    }

    /**
     * Settings for every connection. Before the key: freed native memory with decrypted pages is
     * wiped. After it: temporary tables stay in memory, and deleted records are overwritten, so a
     * deleted password does not linger in free pages.
     */
    private object Hardening : SQLiteDatabaseHook {
        override fun preKey(connection: SQLiteConnection) = apply(connection, "PRAGMA cipher_memory_security = ON")

        override fun postKey(connection: SQLiteConnection) {
            apply(connection, "PRAGMA temp_store = MEMORY")
            apply(connection, "PRAGMA secure_delete = ON")
        }

        // A setting that cannot be applied never keeps the clipboard from opening
        private fun apply(connection: SQLiteConnection, sql: String) {
            try { connection.executeRaw(sql, null, null) } catch (e: RuntimeException) { }
        }
    }
}
