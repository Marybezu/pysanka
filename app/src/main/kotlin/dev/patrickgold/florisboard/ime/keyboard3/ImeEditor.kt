/*
 * Copyright (C) 2026 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.keyboard3

import android.content.ClipDescription
import android.content.ContentUris
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import dev.patrickgold.florisboard.FlorisApplication
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardFileStorage
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardMediaProvider
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.editor.FlorisEditorInfo
import dev.patrickgold.florisboard.ime.editor.ImeOptions
import org.k3lp.runtime.K3Editor
import org.k3lp.runtime.K3SurroundingText
import org.k3lp.runtime.K3TextRange
import java.lang.ref.WeakReference
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

open class ImeEditor(
    val ic: WeakReference<InputConnection>,
    val info: FlorisEditorInfo,
) : K3Editor {
    fun getSurroundingText(charsBefore: Int, charsAfter: Int): K3SurroundingText {
        val ic = ic.get() ?: return K3SurroundingText.Empty
        // TODO maybe use eet for getSurroundingText??
//        val eet = ic.getExtractedText(
//            ExtractedTextRequest().apply {
//                token = -1
//                flags = 0
//                hintMaxLines = 10
//                hintMaxChars = 10000
//            },
//            0,
//        )
//        eet.
        val textBeforeCursor = ic.getTextBeforeCursor(charsBefore, 0) ?: return K3SurroundingText.Empty
        val textAfterCursor = ic.getTextAfterCursor(charsAfter, 0) ?: return K3SurroundingText.Empty
        val textSelected = ic.getSelectedText(0) ?: ""
        //val surroundingText = TextUtils.concat(textBeforeCursor, textSelected, textAfterCursor)
        return K3SurroundingText(
            textBefore = textBeforeCursor.toString(),
            textSelected = textSelected.toString(),
            textAfter = textAfterCursor.toString(),
        )
    }

    // TODO this function is very heavy => we need to optimize k3lp to provide:
    //  insertAtSelection() (for simple appends)
    //  deleteAroundSelection() for bksp/fw delete
    //  in some cases replaceText() is needed due to the transform rules, but we abuse it waaaay too much
    override fun replaceText(
        range: IntRange,
        text: String,
        newSelection: K3TextRange,
        newComposition: K3TextRange?,
    ) {
        val ic = ic.get() ?: return
        ic.beginBatchEdit()
        ic.setComposingRegion(range.first, range.last + 1)
        ic.commitText(text, 1)
        ic.setSelection(newSelection.start, newSelection.end)
        setComposition(newComposition)
        ic.endBatchEdit()
    }

    /** Pysanka: puts text at the cursor as it is (text picked from a photo). */
    fun pysankaInsertText(text: String): Boolean {
        val ic = ic.get() ?: return false
        ic.beginBatchEdit()
        ic.finishComposingText()
        val ok = ic.commitText(text, 1)
        ic.endBatchEdit()
        return ok
    }

    /**
     * Pysanka: text typed on the wrong layout, turned into what was meant: the selection, or else
     * the word just before the cursor (back to the last space). Nothing leaves the keyboard.
     */
    fun pysankaFixLayout(): Boolean {
        val ic = ic.get() ?: return false
        ic.beginBatchEdit()
        try {
            ic.finishComposingText()
            val selected = ic.getSelectedText(0)?.toString()
            if (!selected.isNullOrEmpty()) {
                val fixed = ua.pysanka.typing.LayoutSwitch.convert(selected)
                if (fixed == selected) return false
                // Keep it selected, so a second tap turns it back
                val et = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
                val start = et?.let { it.startOffset + minOf(it.selectionStart, it.selectionEnd) }
                ic.commitText(fixed, 1)
                if (start != null && start >= 0) ic.setSelection(start, start + fixed.length)
                return true
            }
            val before = ic.getTextBeforeCursor(64, 0)?.toString() ?: return false
            // The last word: back to whitespace (punctuation keys are letters on the other layout)
            val word = before.takeLastWhile { !it.isWhitespace() }
            if (word.isEmpty()) return false
            val fixed = ua.pysanka.typing.LayoutSwitch.convert(word)
            if (fixed == word) return false
            ic.deleteSurroundingText(word.length, 0)
            return ic.commitText(fixed, 1)
        } finally {
            ic.endBatchEdit()
        }
    }

    /**
     * Pysanka: the text the AI editor works on: the selection, or else the whole field (up to
     * [limit] + 1 characters, so a longer text can be told apart), with where the selection was
     * (absolute offsets, -1 if the app does not say). Null if the field cannot be read.
     */
    fun pysankaTextForAi(limit: Int): ua.pysanka.ai.AiBridge.Taken? {
        val ic = ic.get() ?: return null
        val et = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest().apply {
            hintMaxChars = limit + 1
            hintMaxLines = Int.MAX_VALUE
        }, 0)
        val start = if (et != null && et.selectionStart >= 0) et.startOffset + minOf(et.selectionStart, et.selectionEnd) else -1
        val end = if (et != null && et.selectionEnd >= 0) et.startOffset + maxOf(et.selectionStart, et.selectionEnd) else -1
        val selected = ic.getSelectedText(0)?.toString()
        if (!selected.isNullOrEmpty()) {
            return ua.pysanka.ai.AiBridge.Taken(ua.pysanka.ai.AiBridge.Mode.SELECTION, selected, start, end)
        }
        val all = wholeText(ic, et, limit)
        return ua.pysanka.ai.AiBridge.Taken(
            if (all.isBlank()) ua.pysanka.ai.AiBridge.Mode.INSERT else ua.pysanka.ai.AiBridge.Mode.ALL, all, start, end,
        )
    }

    /** The whole field: the extracted text if the app gives it, else the text before and after the cursor. */
    private fun wholeText(ic: InputConnection, et: android.view.inputmethod.ExtractedText?, limit: Int): String =
        if (et?.text != null && et.startOffset == 0) {
            et.text.toString()
        } else {
            (ic.getTextBeforeCursor(limit + 1, 0)?.toString() ?: "") +
                (ic.getSelectedText(0)?.toString() ?: "") +
                (ic.getTextAfterCursor(limit + 1, 0)?.toString() ?: "")
        }

    /**
     * Pysanka: puts the AI editor's text back. It replaces the original only if the field still
     * holds exactly it (the selection, found again by its place if the app forgot it, or the whole
     * text). Otherwise nothing is overwritten: the text goes in after the current selection, or,
     * if the app does not say where that is, it is only copied (returns false then).
     */
    fun pysankaApplyAi(result: ua.pysanka.ai.AiBridge.Result): Boolean {
        val ic = ic.get() ?: return false
        // Where the selection is now: from the app, or else as the field reported it when it opened
        val now = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        val curStart: Int
        val curEnd: Int
        if (now != null && now.selectionStart >= 0 && now.selectionEnd >= 0) {
            curStart = now.startOffset + minOf(now.selectionStart, now.selectionEnd)
            curEnd = now.startOffset + maxOf(now.selectionStart, now.selectionEnd)
        } else {
            curStart = minOf(info.initialSelection.start, info.initialSelection.end)
            curEnd = maxOf(info.initialSelection.start, info.initialSelection.end)
        }
        ic.beginBatchEdit()
        try {
            ic.finishComposingText()
            val replace = when (result.mode) {
                ua.pysanka.ai.AiBridge.Mode.SELECTION -> {
                    if (ic.getSelectedText(0)?.toString() == result.original) {
                        true
                    } else if (result.start >= 0 && result.end > result.start && ic.setSelection(result.start, result.end)) {
                        val same = ic.getSelectedText(0)?.toString() == result.original
                        // Not there any more: put the selection back where the person had it
                        if (!same && curStart >= 0) ic.setSelection(curStart, curEnd)
                        same
                    } else {
                        false
                    }
                }
                ua.pysanka.ai.AiBridge.Mode.ALL -> {
                    val et = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest().apply {
                        hintMaxChars = result.original.length + 1
                        hintMaxLines = Int.MAX_VALUE
                    }, 0)
                    wholeText(ic, et, result.original.length) == result.original &&
                        ic.setSelection(0, result.original.length)
                }
                ua.pysanka.ai.AiBridge.Mode.INSERT -> false
            }
            if (replace) return ic.commitText(result.text, 1)
            // Never overwrite something else: go to the end of the selection first; if where it is
            // cannot be told, do not touch the field at all
            if (curStart < 0 || curEnd < 0) {
                if (!ic.getSelectedText(0).isNullOrEmpty()) return false
            } else if (curStart != curEnd) {
                ic.setSelection(curEnd, curEnd)
            }
            return ic.commitText(result.text, 1)
        } finally {
            ic.endBatchEdit()
        }
    }

    fun deleteSurroundingText(charsBefore: Int, charsAfter: Int) {
        val ic = ic.get() ?: return
        ic.deleteSurroundingText(charsBefore, charsAfter)
    }

    fun performClipboardCopy(): Boolean {
        val ic = ic.get() ?: return false
        return ic.performContextMenuAction(android.R.id.copy)
    }

    fun performClipboardCut(): Boolean {
        val ic = ic.get() ?: return false
        return ic.performContextMenuAction(android.R.id.cut)
    }

    fun performClipboardPaste(): Boolean {
        val ic = ic.get() ?: return false
        return ic.performContextMenuAction(android.R.id.paste)
    }

    fun performClipboardPasteMedia(item: ClipboardItem): Boolean {
        val ic = ic.get() ?: return false
        val uri = item.uri ?: return false
        val mimeTypes = item.mimeTypes
        // Pysanka: a clip not kept in the history (a video, or one copied in incognito) points to
        // the other app's own file; the app's normal paste handles it
        if (uri.authority != ClipboardMediaProvider.AUTHORITY) return performClipboardPaste()
        val id = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return performClipboardPaste()
        val appContext = FlorisApplication.getHack() // TODO this is a hack
        val file = ClipboardFileStorage.getFileForId(appContext, id)
        if (!file.exists()) return false
        val inputContentInfo = InputContentInfoCompat(
            uri,
            ClipDescription("clipboard media file", mimeTypes.toTypedArray()),
            null,
        )
        ic.finishComposingText()
        val flags = InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
        return InputConnectionCompat.commitContent(ic, info.base, inputContentInfo, flags, null)
    }

    fun performSelectAll(): Boolean {
        val ic = ic.get() ?: return false
        ic.finishComposingText()
        return if (info.isRawInputEditor) {
            sendDownUpKeyEvent(KeyEvent.KEYCODE_A, meta(ctrl = true))
        } else {
            ic.performContextMenuAction(android.R.id.selectAll)
        }
    }

    fun performUndo(): Boolean {
        return sendDownUpKeyEvent(KeyEvent.KEYCODE_Z, meta(ctrl = true))
    }

    fun performRedo(): Boolean {
        return sendDownUpKeyEvent(KeyEvent.KEYCODE_Z, meta(ctrl = true, shift = true))
    }

    fun performEditorAction(action: ImeOptions.Action) {
        val ic = ic.get() ?: return
        ic.performEditorAction(action.toInt())
    }

    private fun meta(
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
    ): Int {
        var metaState = 0
        if (ctrl) {
            metaState = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        }
        if (alt) {
            metaState = metaState or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        }
        if (shift) {
            metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        }
        return metaState
    }

    private fun InputConnection.sendDownKeyEvent(keyCode: Int, metaState: Int, downTime: Long) {
        sendKeyEvent(
            KeyEvent(
                downTime, downTime,
                KeyEvent.ACTION_DOWN, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
    }

    private fun InputConnection.sendUpKeyEvent(keyCode: Int, metaState: Int, downTime: Long, upTime: Long) {
        sendKeyEvent(
            KeyEvent(
                downTime, upTime,
                KeyEvent.ACTION_UP, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
    }

    fun sendDownUpKeyEvent(keyCode: Int, metaState: Int = 0): Boolean {
        val ic = ic.get() ?: return false
        val downTime = SystemClock.uptimeMillis()
        ic.sendDownKeyEvent(keyCode, metaState, downTime)
        val upTime = SystemClock.uptimeMillis()
        ic.sendUpKeyEvent(keyCode, metaState, downTime, upTime)
        return true
    }

    override fun setComposition(newComposition: K3TextRange?) {
        val ic = ic.get() ?: return
        if (newComposition == null || newComposition.isCollapsed()) {
            ic.finishComposingText()
        } else {
            ic.setComposingRegion(newComposition.start, newComposition.end)
        }
    }

    private inline fun InputConnection.batchEdit(block: () -> Unit) {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }
        requestCursorUpdates(0)
        beginBatchEdit()
        block()
        endBatchEdit()
        requestCursorUpdates(CURSOR_UPDATES)
    }

    companion object {
        /**
         * The input connection update cursor flags.
         *
         * MUST NOT contain filter flags, as these behave strangely with some editors!!
         */
        const val CURSOR_UPDATES = InputConnection.CURSOR_UPDATE_MONITOR or InputConnection.CURSOR_UPDATE_IMMEDIATE

        val Disconnected = ImeEditor(
            ic = WeakReference(null),
            info = FlorisEditorInfo.Unspecified,
        )
    }
}
