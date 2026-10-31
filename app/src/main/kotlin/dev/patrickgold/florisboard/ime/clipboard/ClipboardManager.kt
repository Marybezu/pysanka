/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.clipboard

import android.content.ClipData
import android.content.Context
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardHistoryDao
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardHistoryDatabase
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.florisboard.lib.android.AndroidClipboardManager
import org.florisboard.lib.android.AndroidClipboardManager_OnPrimaryClipChangedListener
import org.florisboard.lib.android.clearPrimaryClipAnyApi
import org.florisboard.lib.android.setOrClearPrimaryClip
import org.florisboard.lib.android.systemService
import org.florisboard.lib.kotlin.tryOrNull

/**
 * [ClipboardManager] manages the clipboard and clipboard history.
 *
 * Also just going to document how all the classes here work.
 *
 * [ClipboardManager] handles storage and retrieval of clipboard items. All manipulation of the
 * clipboard goes through here.
 */
class ClipboardManager(
    context: Context,
) : AndroidClipboardManager_OnPrimaryClipChangedListener, Closeable {
    companion object {
        // 1 minute
        private const val INTERVAL = 60 * 1000L

        /**
         * Taken from ClipboardDescription.java from the AOSP
         *
         * Helper to compare two MIME types, where one may be a pattern.
         * @param concreteType A fully-specified MIME type.
         * @param desiredType A desired MIME type that may be a pattern such as * / *.
         * @return Returns true if the two MIME types match.
         */
        fun compareMimeTypes(concreteType: String, desiredType: String): Boolean {
            val typeLength = desiredType.length
            if (typeLength == 3 && desiredType == "*/*") {
                return true
            }
            val slashpos = desiredType.indexOf('/')
            if (slashpos > 0) {
                if (typeLength == slashpos + 2 && desiredType[slashpos + 1] == '*') {
                    if (desiredType.regionMatches(0, concreteType, 0, slashpos + 1)) {
                        return true
                    }
                } else if (desiredType == concreteType) {
                    return true
                }
            }
            return false
        }
    }

    private val prefs by FlorisPreferenceStore
    private val appContext by context.appContext()
    private val systemClipboardManager = context.systemService(AndroidClipboardManager::class)

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cleanUpJob: Job
    private var clipHistoryDb: ClipboardHistoryDatabase? = null
    private val clipHistoryDao: ClipboardHistoryDao? get() = clipHistoryDb?.clipboardItemDao()

    val historyFlow: StateFlow<ClipboardHistory>
        field = MutableStateFlow(ClipboardHistory.EMPTY)
    val currentHistory: ClipboardHistory
        get() = historyFlow.value

    /**
     * Pysanka: what kind of field the keyboard is in. Set on the main thread when a field opens or
     * closes, and read on the main thread when a copy happens, so a copy is judged by its own field.
     */
    @Volatile
    private var fieldIncognito = false
    @Volatile
    private var fieldPassword = false

    fun setFieldPrivacy(incognito: Boolean, passwordField: Boolean) {
        fieldIncognito = incognito
        fieldPassword = passwordField
    }

    /**
     * Pysanka: whether a clip may enter the history. Incognito (and fields that ask for no learning)
     * never; passwords and sensitive clips only when the owner turned "Don't keep passwords" off.
     */
    private fun mayKeep(isSensitive: Boolean, incognito: Boolean, passwordField: Boolean): Boolean {
        if (!prefs.clipboard.historyEnabled.get() || incognito) return false
        if ((isSensitive || passwordField) && prefs.clipboard.historySkipSensitive.get()) return false
        return true
    }

    private val primaryClipLastFromCallbackGuard = Mutex(locked = false)
    private var primaryClipLastFromCallback: ClipData? = null
    val primaryClipFlow: StateFlow<ClipboardItem?>
        field = MutableStateFlow(null)
    inline var primaryClip
        get() = primaryClipFlow.value
        private set(v) {
            primaryClipFlow.value = v
        }

    init {
        systemClipboardManager.addPrimaryClipChangedListener(this)
        cleanUpJob = ioScope.launch {
            while (isActive) {
                delay(INTERVAL)
                enforceExpiryDate(currentHistory)
            }
        }
    }

    fun initializeForContext(context: Context) {
        ioScope.launch {
            if (clipHistoryDb == null) {
                // Pysanka: the history is encrypted; while the phone is locked the vault stays shut,
                // so try again as soon as it is unlocked
                if (!ua.pysanka.clipboard.SecureClipboard.ensureOpen(context)) {
                    retryWhenUnlocked(context.applicationContext)
                    return@launch
                }
                clipHistoryDb = ClipboardHistoryDatabase.new(context.applicationContext)
                // Pysanka: open the encrypted file list now, in the background, so pasting an
                // image later (a call on the main thread) never waits for it
                runCatching {
                    val client = context.applicationContext.contentResolver
                        .acquireContentProviderClient(dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardMediaProvider.AUTHORITY)
                    try {
                        (client?.localContentProvider as? dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardMediaProvider)?.warmUp()
                    } finally {
                        client?.close()
                    }
                }
                withContext(Dispatchers.Main) {
                    clipHistoryDao?.getAllAsFlow()?.collect { items ->
                        updateHistory(items)
                    }
                }
            }
        }
    }

    private var unlockReceiver: android.content.BroadcastReceiver? = null

    @Synchronized
    private fun retryWhenUnlocked(appContext: Context) {
        if (unlockReceiver != null) return
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: android.content.Intent?) {
                synchronized(this@ClipboardManager) {
                    runCatching { appContext.unregisterReceiver(this) }
                    unlockReceiver = null
                }
                initializeForContext(appContext)
            }
        }
        unlockReceiver = receiver
        // A system broadcast; registering for it needs no permission
        androidx.core.content.ContextCompat.registerReceiver(
            appContext, receiver, android.content.IntentFilter(android.content.Intent.ACTION_USER_PRESENT),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun updateHistory(items: List<ClipboardItem>) {
        val itemsSorted = items.sortedByDescending { it.creationTimestampMs }
        val clipHistory = ClipboardHistory(itemsSorted)
        enforceHistoryLimit(clipHistory)
        historyFlow.value = clipHistory
    }

    /**
     * Sets the current primary clip without updating the internal clipboard history.
     */
    fun updatePrimaryClip(item: ClipboardItem?) {
        primaryClip = item
        if (prefs.clipboard.useInternalClipboard.get()) {
            val syncBehavior = prefs.clipboard.syncToSystem.get()
            val clipData = item?.toClipData(appContext)
            if (clipData != null && syncBehavior.shouldSyncSet) {
                systemClipboardManager.setPrimaryClip(clipData)
            } else if (clipData == null && syncBehavior.shouldSyncClear) {
                systemClipboardManager.clearPrimaryClipAnyApi()
            }
        } else {
            systemClipboardManager.setOrClearPrimaryClip(item?.toClipData(appContext))
        }
    }

    /**
     * Called by system clipboard when the system primary clip has changed.
     */
    override fun onPrimaryClipChanged() {
        val syncBehavior = prefs.clipboard.syncToFloris.get()
        if (!prefs.clipboard.useInternalClipboard.get() || syncBehavior != ClipboardSyncBehavior.NO_EVENTS) {
            val systemPrimaryClip = systemClipboardManager.primaryClip
            // Pysanka: the field at the moment of copying, not when the background job runs
            val incognito = fieldIncognito
            val passwordField = fieldPassword
            ioScope.launch {
                val isDuplicate: Boolean
                primaryClipLastFromCallbackGuard.withLock {
                    val a = primaryClipLastFromCallback?.getItemAt(0)
                    val b = systemPrimaryClip?.getItemAt(0)
                    isDuplicate = when {
                        a === b -> true
                        a == null || b == null -> false
                        else -> a.text == b.text && a.uri == b.uri
                    }
                    primaryClipLastFromCallback = systemPrimaryClip
                }
                if (isDuplicate) return@launch

                val internalPrimaryClip = primaryClip

                if (systemPrimaryClip == null) {
                    if (syncBehavior.shouldSyncClear) {
                        primaryClip = null
                    }
                    return@launch
                }

                if (systemPrimaryClip.getItemAt(0).let { it.text == null && it.uri == null }) {
                    if (syncBehavior.shouldSyncClear) {
                        primaryClip = null
                    }
                    return@launch
                }

                if (!syncBehavior.shouldSyncSet) {
                    return@launch
                }

                val isEqual = internalPrimaryClip?.isEqualTo(systemPrimaryClip) == true
                if (!isEqual) {
                    // Pysanka: decide before anything is stored, so a clip that stays out of the
                    // history never leaves an (encrypted) copy of its image behind either
                    // Pysanka: videos never enter the history (they can be gigabytes); images do, up to
                    // ClipboardFileStorage.MAX_IMAGE_BYTES
                    val isVideo = systemPrimaryClip.getItemAt(0).uri != null &&
                        systemPrimaryClip.description.hasMimeType("video/*") &&
                        !systemPrimaryClip.description.hasMimeType("image/*")
                    val keep = !isVideo && mayKeep(ClipboardItem.isSensitive(systemPrimaryClip), incognito, passwordField)
                    val item = ClipboardItem.fromClipData(appContext, systemPrimaryClip, cloneUri = keep).let {
                        // Kept although copied in a password field ("Don't keep passwords" off): mark it
                        // sensitive, so it is masked and the timed clean-up of sensitive items applies
                        if (passwordField && !it.isSensitive) it.copy(isSensitive = true) else it
                    }
                    primaryClip = item
                    // An image that could not be stored (too large, unreadable) gets id 0: not kept
                    val stored = item.type == ItemType.TEXT ||
                        item.uri?.let { runCatching { android.content.ContentUris.parseId(it) }.getOrNull() }?.let { it > 0 } == true
                    if (keep && stored) insertOrMoveBeginning(item)
                }
            }
        }
    }

    /**
     * Change the current text on clipboard, update history (if enabled).
     */
    private fun addNewClip(item: ClipboardItem) {
        // Pysanka: see mayKeep (a copy from the keyboard itself happens in the field open right now)
        if (mayKeep(item.isSensitive, fieldIncognito, fieldPassword)) {
            insertOrMoveBeginning(if (fieldPassword && !item.isSensitive) item.copy(isSensitive = true) else item)
        }
        updatePrimaryClip(item)
    }

    /**
     * Wraps some plaintext in a ClipData and calls [addNewClip]
     */
    fun addNewPlaintext(newText: String) {
        val newData = ClipboardItem.text(newText)
        addNewClip(newData)
    }

    /**
     * Adds a new item to the clipboard history (if enabled).
     */
    private fun insertOrMoveBeginning(newItem: ClipboardItem) {
        if (prefs.clipboard.historyEnabled.get()) {
            val historyElement = currentHistory.all.firstOrNull { item ->
                item.type == ItemType.TEXT && item.text == newItem.text && item.isSensitive == newItem.isSensitive
            }
            if (historyElement != null) {
                moveToTheBeginning(
                    oldItem = historyElement,
                    newItem = if (historyElement.isPinned) {
                        newItem.copy(isPinned = true)
                    } else {
                        newItem
                    }
                )
            } else {
                insertClip(newItem)
            }
        }
    }

    private fun enforceHistoryLimit(clipHistory: ClipboardHistory) {
        if (prefs.clipboard.historySizeLimitEnabled.get()) {
            val nonPinnedItems = clipHistory.recent + clipHistory.other
            val nToRemove = nonPinnedItems.size - prefs.clipboard.historySizeLimit.get()
            if (nToRemove > 0) {
                val itemsToRemove = nonPinnedItems.asReversed().filterIndexed { n, _ -> n < nToRemove }
                ioScope.launch {
                    clipHistoryDao?.delete(itemsToRemove)
                }
            }
        }
    }

    private fun enforceExpiryDate(clipHistory: ClipboardHistory) {
        val itemsToRemove = mutableSetOf<ClipboardItem>()
        if (prefs.clipboard.historyAutoCleanOldEnabled.get()) {
            val nonPinnedItems = clipHistory.recent + clipHistory.other
            val expiryTime = System.currentTimeMillis() - (prefs.clipboard.historyAutoCleanOldAfter.get() * 60 * 1000)
            itemsToRemove.addAll(nonPinnedItems.filter { it.creationTimestampMs < expiryTime })
        }
        if (prefs.clipboard.historyAutoCleanSensitiveEnabled.get()) {
            val sensitiveData = clipHistory.all.filter { it.isSensitive }
            val expiryTime = System.currentTimeMillis() - (prefs.clipboard.historyAutoCleanSensitiveAfter.get() * 1000)
            itemsToRemove.addAll(sensitiveData.filter { it.creationTimestampMs < expiryTime })
        }
        if (itemsToRemove.isNotEmpty()) {
            ioScope.launch {
                clipHistoryDao?.delete(itemsToRemove.toList())
            }
        }
    }

    private fun moveToTheBeginning(oldItem: ClipboardItem, newItem: ClipboardItem) {
        ioScope.launch {
            clipHistoryDao?.delete(oldItem.id)
            clipHistoryDao?.insert(newItem)
        }
    }

    fun insertClip(item: ClipboardItem) {
        ioScope.launch {
            val id = clipHistoryDao?.insert(item)
            item.id = id ?: 0
        }
    }

    fun clearExactHistory(items: List<ClipboardItem>) {
        ioScope.launch {
            for (item in items) {
                item.close(appContext)
            }
            clipHistoryDao?.delete(items)
        }
    }

    /**
     * Clears all unpinned items from the clipboard history
     */
    fun clearHistory() {
        ioScope.launch {
            for (item in currentHistory.all) {
                item.close(appContext)
            }
            clipHistoryDao?.deleteAllUnpinned()
        }
    }

    /**
     * Clears the full clipboard history
     */
    fun clearFullHistory() {
        ioScope.launch {
            for (item in currentHistory.all) {
                item.close(appContext)
            }
            clipHistoryDao?.deleteAll()
        }
    }


    /**
     * Restore the clipboard history from a [List]
     *
     * @param items the [ClipboardItem] list with the new items
     */
    /**
     * Pysanka: adds imported items, waiting (a few seconds at most) for the encrypted history to
     * open. Text already in the history is not added twice; passwords are skipped while
     * "Don't keep passwords" is on. Returns how many were added and how many skipped.
     */
    suspend fun importItems(items: List<ClipboardItem>): Pair<Int, Int> = withContext(Dispatchers.IO) {
        var waited = 0
        while (clipHistoryDao == null && waited < 10_000) {
            delay(200)
            waited += 200
        }
        val dao = checkNotNull(clipHistoryDao) { "Clipboard is locked" }
        val skipSensitive = prefs.clipboard.historySkipSensitive.get()
        val existingTexts = dao.getAll().filter { it.type == ItemType.TEXT }.mapNotNull { it.text }.toHashSet()
        var added = 0
        var skipped = 0
        for (item in items) {
            val duplicate = item.type == ItemType.TEXT && item.text in existingTexts
            if (duplicate || item.type == ItemType.VIDEO || (item.isSensitive && skipSensitive)) {
                skipped++
                // An image stored for an item that is not added must not stay behind
                if (item.type != ItemType.TEXT) item.uri?.let { runCatching { appContext.contentResolver.delete(it, null, null) } }
                continue
            }
            dao.insert(item.copy(id = 0))
            item.text?.let { existingTexts.add(it) }
            added++
        }
        added to skipped
    }

    fun restoreHistory(items: List<ClipboardItem>) {
        ioScope.launch {
            val currentHistory = currentHistory.all
            for (item in items) {
                if (!currentHistory.map { it.copy(id = 0) }.contains(item.copy(id = 0))) {
                    insertClip(item.copy(id = 0))
                }
            }
        }
    }

    fun deleteClip(item: ClipboardItem, onlyIfUnpinned: Boolean) {
        ioScope.launch {
            if (onlyIfUnpinned) {
                clipHistoryDao?.deleteIfUnpinned(item.id)
            } else {
                clipHistoryDao?.delete(item.id)
            }
            tryOrNull {
                val uri = item.uri
                if (uri != null) {
                    appContext.contentResolver.delete(uri, null, null)
                }
            }
        }
    }

    fun pinClip(item: ClipboardItem) {
        ioScope.launch {
            clipHistoryDao?.update(item.copy(isPinned = true))
        }
    }

    fun unpinClip(item: ClipboardItem) {
        ioScope.launch {
            clipHistoryDao?.update(item.copy(isPinned = false))
        }
    }

    fun pasteItem(item: ClipboardItem) {
        val imeController = appContext.imeController
        // TODO this asks for a deadlock from ImeController update scope
        imeController.updateStateBlocking {
            emitClipboardItem(item).also { result ->
                if (!result) {
                    // TODO we have no access to interactionController here
                    //appContext.showShortToastSync("Failed to paste item.")
                }
            }
        }
    }

    /**
     * Returns true if the editor can accept the clip item, else false.
     */
    fun canBePasted(clipItem: ClipboardItem?): Boolean {
        val imeController = appContext.imeController
        val info = imeController.snapshotState().editor.info
        return clipItem != null && (clipItem.mimeTypes.contains("text/plain") || info.contentMimeTypes.any { editorType ->
            clipItem.mimeTypes.any { clipType ->
                compareMimeTypes(clipType, editorType)
            }
        })
    }

    /**
     * Cleans up.
     *
     * Unregisters the system clipboard listener, cancels clipboard clean ups.
     */
    override fun close() {
        systemClipboardManager.removePrimaryClipChangedListener(this)
        cleanUpJob.cancel()
    }
}
