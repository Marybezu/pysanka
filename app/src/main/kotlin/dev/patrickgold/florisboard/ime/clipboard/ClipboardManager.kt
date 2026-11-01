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
import kotlinx.coroutines.ensureActive
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

        /** Pysanka: longest text accepted from an import (a longer row would break loading the history). */
        const val MAX_IMPORT_TEXT = 512 * 1024

        /** Pysanka: longest recognised text kept with a picture (for finding it later). */
        const val MAX_IMAGE_TEXT = 20_000

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
                // Pysanka: the owner's tags, kept up to date for the keyboard's tag slider
                launch {
                    clipHistoryDb?.clipboardTagDao()?.getAllAsFlow()?.collect { tags ->
                        tagsFlow.value = tags.map { ua.pysanka.clipboard.TagView(it.id, it.name, it.color) }
                    }
                }
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

                // Pysanka: a copied link loses its tracking parameters, in the system clipboard too
                // (so it is clean wherever it is pasted). Not for passwords or marked-sensitive clips.
                // Setting the clean text calls this listener again; that time nothing changes.
                if (prefs.clipboard.cleanLinks.get() && !passwordField &&
                    !ClipboardItem.isSensitive(systemPrimaryClip) && systemPrimaryClip.itemCount == 1
                ) {
                    val text = systemPrimaryClip.getItemAt(0).text?.toString()
                    if (text != null && systemPrimaryClip.getItemAt(0).uri == null && text.length <= MAX_IMPORT_TEXT) {
                        val clean = ua.pysanka.clipboard.LinkCleaner.clean(text)
                        if (clean != text) {
                            val label = systemPrimaryClip.description.label
                            withContext(Dispatchers.Main) {
                                runCatching { systemClipboardManager.setPrimaryClip(ClipData.newPlainText(label, clean)) }
                            }
                            return@launch
                        }
                    }
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
                    // Pysanka: an image's stored file goes with its item, never left behind
                    itemsToRemove.forEach { it.close(appContext) }
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
                // Pysanka: an image's stored file goes with its item, never left behind
                itemsToRemove.forEach { it.close(appContext) }
                clipHistoryDao?.delete(itemsToRemove.toList())
            }
        }
    }

    private fun moveToTheBeginning(oldItem: ClipboardItem, newItem: ClipboardItem) {
        ioScope.launch {
            clipHistoryDao?.delete(oldItem.id)
            // Pysanka: copying the same text again keeps its tags
            clipHistoryDao?.insert(newItem.copy(tags = oldItem.tags))
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
    /** Pysanka: what an import did. */
    data class ImportOutcome(val added: Int, val skipped: Int, val overLimit: Int)

    class HistoryDisabled : Exception()

    /**
     * Pysanka: adds imported items to the history.
     *
     * [item] describes a candidate (for an image, with no uri yet); [materialize] turns an accepted
     * candidate into the item to store (registering its image file); [discard] removes whatever a
     * candidate stored that will not be used. Text already present is not added twice; videos, very
     * long texts and (while "Don't keep passwords" is on) sensitive items are skipped. With the
     * history size limit on, only as many unpinned items as fit are added (the newest), so nothing
     * already in the history is pushed out. Imported items get fresh times in their original order,
     * so the automatic clean-up of old items does not remove them at once.
     */
    suspend fun <T> importCandidates(
        candidates: List<T>,
        item: (T) -> ClipboardItem,
        materialize: (T) -> ClipboardItem,
        discard: (T) -> Unit,
    ): ImportOutcome = withContext(Dispatchers.IO) {
        val pending = candidates.toMutableList()
        try {
            var waited = 0
            while (clipHistoryDao == null && waited < 10_000) {
                delay(200)
                waited += 200
            }
            val dao = checkNotNull(clipHistoryDao) { "Clipboard is locked" }
            if (!prefs.clipboard.historyEnabled.get()) throw HistoryDisabled()
            val skipSensitive = prefs.clipboard.historySkipSensitive.get()
            val current = dao.getAll()
            val texts = current.filter { it.type == ItemType.TEXT }.mapNotNull { it.text }.toHashSet()
            var skipped = 0
            val accepted = ArrayList<T>()
            for (c in candidates.sortedBy { item(it).creationTimestampMs }) {
                val i = item(c)
                val bad = i.type == ItemType.VIDEO ||
                    (i.type == ItemType.TEXT && (i.text == null || i.text.length > MAX_IMPORT_TEXT || i.text in texts)) ||
                    (i.isSensitive && skipSensitive)
                if (bad) {
                    skipped++
                    discard(c)
                    pending.remove(c)
                } else {
                    accepted.add(c)
                    if (i.type == ItemType.TEXT) i.text?.let { texts.add(it) }
                }
            }
            var overLimit = 0
            val final = if (prefs.clipboard.historySizeLimitEnabled.get()) {
                val free = maxOf(0, prefs.clipboard.historySizeLimit.get() - current.count { !it.isPinned })
                val unpinned = accepted.filter { !item(it).isPinned }
                val dropped = unpinned.dropLast(free).toHashSet()
                overLimit = dropped.size
                dropped.forEach { discard(it); pending.remove(it) }
                accepted.filter { it !in dropped }
            } else {
                accepted
            }
            val now = System.currentTimeMillis()
            for ((n, c) in final.withIndex()) {
                ensureActive()
                val stored = materialize(c)
                dao.insert(stored.copy(id = 0, creationTimestampMs = now - (final.size - n)))
                pending.remove(c)
            }
            ImportOutcome(final.size, skipped, overLimit)
        } catch (t: Throwable) {
            // Nothing a failed or cancelled import stored may stay behind
            pending.forEach { runCatching { discard(it) } }
            throw t
        }
    }

    // ------------------------------------------------------------ Pysanka: a photo into the history

    /** Whether the encrypted history is open (it stays shut until the phone is first unlocked). */
    fun isHistoryOpen(): Boolean = clipHistoryDao != null

    /**
     * Pysanka: stores the picture at [uri] (one the person picked) encrypted and adds it to the
     * history. The system clipboard is not changed. Returns the new item's id, or null if it was
     * not added. Call off the main thread.
     */
    fun addImage(context: Context, uri: android.net.Uri): Long? {
        val dao = clipHistoryDao ?: return null
        if (!prefs.clipboard.historyEnabled.get()) return null
        val mime = context.contentResolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
        val stored = context.contentResolver.openInputStream(uri)?.use {
            dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardFileStorage.storeStream(appContext, it)
        } ?: return null
        var itemUri: android.net.Uri? = null
        try {
            val client = checkNotNull(appContext.contentResolver.acquireContentProviderClient(
                dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardMediaProvider.AUTHORITY))
            val provider = try {
                client.localContentProvider as dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardMediaProvider
            } finally {
                client.close()
            }
            // A plain name: the original one may tell the app and the time, and an app it is pasted into could read it
            val name = "Image." + (android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg")
            itemUri = provider.registerImported(
                dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardFileInfo(stored.id, name, stored.size, 0, listOf(mime)),
            )
            val item = ClipboardItem(
                type = ItemType.IMAGE, text = null, uri = itemUri, creationTimestampMs = System.currentTimeMillis(),
                isPinned = false, mimeTypes = listOf(mime), isSensitive = false,
            )
            return dao.insert(item)
        } catch (t: Throwable) {
            // Not in the history: its file (and its entry in the file list) goes too
            val registered = itemUri
            if (registered != null) {
                runCatching { appContext.contentResolver.delete(registered, null, null) }
            } else {
                runCatching { dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardFileStorage.deleteById(appContext, stored.id) }
            }
            throw t
        }
    }

    /** Pysanka: keeps the text recognised on a screenshot with it, for finding it later. */
    fun setImageText(id: Long, text: String) {
        clipHistoryDao?.setImageText(id, text)
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

    // ------------------------------------------------------------ Pysanka: tags

    /** The owner's own tags, in their order. Empty until the encrypted history is open. */
    val tagsFlow: StateFlow<List<ua.pysanka.clipboard.TagView>>
        field = MutableStateFlow(emptyList())

    private val tagDao get() = clipHistoryDb?.clipboardTagDao()

    /** Tag changes run one after another, so quick taps cannot mix up the order. */
    private val tagMutex = Mutex()

    private fun tagJob(block: suspend () -> Unit) {
        ioScope.launch { tagMutex.withLock { block() } }
    }

    /**
     * Pysanka: for import: the ids of tags with these names, creating the missing ones with the
     * given colours. Names are compared without case.
     */
    suspend fun ensureTags(defs: List<Pair<String, Int>>): Map<String, Long> = tagMutex.withLock {
        withContext(Dispatchers.IO) {
            val dao = checkNotNull(tagDao) { "Clipboard is locked" }
            val existing = dao.getAll()
            val byName = existing.associate { it.name.lowercase() to it.id }.toMutableMap()
            var position = (existing.maxOfOrNull { it.position } ?: -1) + 1
            for ((name, color) in defs) {
                val clean = name.trim().take(40)
                if (clean.isEmpty() || clean.lowercase() in byName) continue
                byName[clean.lowercase()] = dao.insert(
                    dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardTag(name = clean, color = color, position = position++),
                )
            }
            byName
        }
    }

    /** Pysanka: for export: the owner's tags as they are stored. */
    suspend fun allTags(): List<dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardTag> =
        withContext(Dispatchers.IO) { tagDao?.getAll() ?: emptyList() }


    fun addTag(name: String, color: Int) {
        val clean = name.trim().take(40)
        if (clean.isEmpty()) return
        tagJob {
            val dao = tagDao ?: return@tagJob
            val position = (dao.getAll().maxOfOrNull { it.position } ?: -1) + 1
            dao.insert(dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardTag(name = clean, color = color, position = position))
        }
    }

    fun updateTag(id: Long, name: String, color: Int) {
        val clean = name.trim().take(40)
        if (clean.isEmpty()) return
        tagJob {
            val dao = tagDao ?: return@tagJob
            val tag = dao.getAll().firstOrNull { it.id == id } ?: return@tagJob
            dao.update(tag.copy(name = clean, color = color))
        }
    }

    /** Moves a tag one place earlier ([up]) or later in the slider. */
    fun moveTag(id: Long, up: Boolean) {
        tagJob {
            val dao = tagDao ?: return@tagJob
            val list = dao.getAll().toMutableList()
            val i = list.indexOfFirst { it.id == id }
            val j = if (up) i - 1 else i + 1
            if (i < 0 || j !in list.indices) return@tagJob
            java.util.Collections.swap(list, i, j)
            list.forEachIndexed { n, t -> if (t.position != n) dao.update(t.copy(position = n)) }
        }
    }

    /** Deletes a tag; the items it was on stay, only without it. */
    fun deleteTag(id: Long) {
        tagJob {
            val dao = tagDao ?: return@tagJob
            dao.untagAll(id)
            dao.clearEmptyTags()
            dao.delete(id)
        }
    }

    fun setItemTags(item: ClipboardItem, ids: Collection<Long>) {
        ioScope.launch {
            clipHistoryDao?.setTags(item.id, item.withTagIds(ids).tags)
        }
    }

    fun pinClip(item: ClipboardItem) {
        ioScope.launch {
            clipHistoryDao?.setPinned(item.id, true)
        }
    }

    fun unpinClip(item: ClipboardItem) {
        ioScope.launch {
            clipHistoryDao?.setPinned(item.id, false)
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
