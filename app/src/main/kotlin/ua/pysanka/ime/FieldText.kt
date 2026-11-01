package ua.pysanka.ime

import android.view.inputmethod.InputConnection
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.ime.editor.FlorisEditorInfo

/**
 * Pysanka: what Pysanka's own features do with the text field the keyboard is in (text from a
 * photo, fixing the layout, the AI). Each call works on the field connected right now.
 */
object FieldText {
    private fun ic(): InputConnection? = FlorisImeService.currentInputConnection()

    /** Puts text at the cursor as it is (text picked from a photo). */
    fun insert(text: String): Boolean {
        val ic = ic() ?: return false
        ic.beginBatchEdit()
        ic.finishComposingText()
        val ok = ic.commitText(text, 1)
        ic.endBatchEdit()
        return ok
    }

    /**
     * Text typed on the wrong layout, turned into what was meant: the selection, or else
     * the word just before the cursor (back to the last space). Nothing leaves the keyboard.
     */
    fun fixLayout(): Boolean {
        val ic = ic() ?: return false
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
            // The last word: back to whitespace (punctuation keys are letters on the other layout);
            // a space just typed after it (the usual moment a mistake is seen) stays as it is
            val trailing = before.takeLastWhile { it == ' ' }
            val word = before.dropLast(trailing.length).takeLastWhile { !it.isWhitespace() }
            if (word.isEmpty()) return false
            val fixed = ua.pysanka.typing.LayoutSwitch.convert(word)
            if (fixed == word) return false
            ic.deleteSurroundingText(word.length + trailing.length, 0)
            return ic.commitText(fixed + trailing, 1)
        } finally {
            ic.endBatchEdit()
        }
    }

    /**
     * The text the AI works on: the selection, or else the whole field (up to
     * [limit] + 1 characters, so a longer text can be told apart), with where the selection was
     * (absolute offsets, -1 if the app does not say). Null if the field cannot be read.
     */
    fun textForAi(limit: Int): ua.pysanka.ai.AiBridge.Taken? {
        val ic = ic() ?: return null
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
     * Puts the AI's text back. It replaces the original only if the field still
     * holds exactly it (the selection, found again by its place if the app forgot it, or the whole
     * text). Otherwise nothing is overwritten: the text goes in after the current selection, or,
     * if the app does not say where that is, it is only copied (returns false then).
     */
    fun applyAi(info: FlorisEditorInfo, result: ua.pysanka.ai.AiBridge.Result): Boolean {
        val ic = ic() ?: return false
        // Where the selection is now: from the app, or else as the field reported it when it opened
        val now = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        val curStart: Int
        val curEnd: Int
        if (now != null && now.selectionStart >= 0 && now.selectionEnd >= 0) {
            curStart = now.startOffset + minOf(now.selectionStart, now.selectionEnd)
            curEnd = now.startOffset + maxOf(now.selectionStart, now.selectionEnd)
        } else {
            // As the field reported it when it opened; -1 when it did not say
            curStart = minOf(info.base.initialSelStart, info.base.initialSelEnd)
            curEnd = maxOf(info.base.initialSelStart, info.base.initialSelEnd)
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
                // Where the cursor is cannot be told: only an empty field is safe to write into
                if (result.mode != ua.pysanka.ai.AiBridge.Mode.INSERT || !ic.getSelectedText(0).isNullOrEmpty()) return false
            } else if (curStart != curEnd) {
                ic.setSelection(curEnd, curEnd)
            }
            return ic.commitText(result.text, 1)
        } finally {
            ic.endBatchEdit()
        }
    }
}
