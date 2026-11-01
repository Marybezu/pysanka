package ua.pysanka.ai

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.widget.Toast
import dev.patrickgold.florisboard.FlorisApplication
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.editor.FlorisEditorInfo
import ua.pysanka.privacy.FieldPrivacy

/**
 * Pysanka: the keyboard's side of the AI editor (in the keyboard's process).
 *
 * The editor runs in its own process (":ai"), so the keyboard hands it the text in the intent and
 * gets the answer back through a [ResultReceiver] (a binder, nothing is written anywhere). The
 * answer waits in memory until the keyboard is back in the field it came from (same app, same
 * field, not a password field, within [MAX_AGE_MS]), and is dropped otherwise.
 *
 * Never for password fields, incognito or fields that ask for no learning.
 */
object AiBridge {
    /** The longest text the editor takes (a long one is done in parts; see [AiJob]). */
    const val MAX_CHARS = AiJob.HARD_MAX

    /** From pressing "Replace" to the keyboard being back in the field: seconds, not minutes. */
    private const val MAX_AGE_MS = 60 * 1000L

    /** What the answer replaces: the selection, the whole text, or nothing (it goes in at the cursor). */
    enum class Mode { SELECTION, ALL, INSERT }

    /** [start]/[end]: where the selection was (absolute offsets, -1 if unknown). */
    data class Taken(val mode: Mode, val text: String, val start: Int = -1, val end: Int = -1)

    data class Result(val mode: Mode, val original: String, val text: String, val start: Int, val end: Int)

    /**
     * The field the text came from. Apps with web or Compose fields often give every field the
     * same id, so its name, input type and hint must match too.
     */
    private data class Field(val packageName: String?, val fieldId: Int, val fieldName: String?, val inputType: Int, val hint: String?)

    private class Target(val field: Field, val taken: Taken, val session: Long)

    private fun fieldOf(info: FlorisEditorInfo) = Field(
        info.packageName, info.base.fieldId, info.base.fieldName, info.base.inputType, info.base.hintText?.toString(),
    )

    private var target: Target? = null
    private var sessions = 0L
    private var answer: String? = null
    private var at = 0L

    private fun toast(res: Int) {
        val app = FlorisApplication.getHack()
        Toast.makeText(app, res, Toast.LENGTH_LONG).show()
    }

    /** What the panel works on, from pressing ✦ until the panel takes it. */
    class Panel(val info: FlorisEditorInfo, val taken: Taken) {
        /** In Pysanka's own fields the full editor is not offered (it could be the editor's own field). */
        val canExpand get() = info.packageName != FlorisApplication.getHack().packageName
    }

    private var panel: Panel? = null

    /**
     * The "AI" button on the keyboard: reads the text for the panel (see [AiPanel]). False if the
     * panel must not open here (then the person is told why).
     */
    fun openPanel(info: FlorisEditorInfo): Boolean {
        if (FieldPrivacy.isPassword || FieldPrivacy.isPrivate) {
            toast(R.string.pysanka__ai__not_here)
            return false
        }
        val taken = ua.pysanka.ime.FieldText.textForAi(MAX_CHARS) ?: return false
        if (taken.text.length > MAX_CHARS) {
            toast(R.string.pysanka__ai__too_long)
            return false
        }
        panel = Panel(info, taken)
        return true
    }

    /** The panel takes its text once; nothing of it stays here. */
    fun takePanel(): Panel? = panel.also { panel = null }

    /**
     * "Expand" in the panel: the full editor on [text] (the field's text, or what the panel made of
     * it), its answer going back to the field as from the panel.
     */
    /** [done]: an answer the panel already has, shown at once. */
    fun openEditor(
        info: FlorisEditorInfo, taken: Taken, text: String, preset: AiAction,
        done: AiJob.Output? = null, target: AiLanguage? = null,
    ) {
        open(fieldOf(info), taken, text, preset, done, target)
    }

    /**
     * From "text from a photo": the recognised [text] goes to the editor (with "fix recognised
     * text" chosen), and its answer goes in at the cursor of the field the photo screen was opened
     * from (that screen remembered it in [ua.pysanka.ocr.PendingInsert]).
     */
    fun openFromPhoto(context: Context, text: String) {
        // Without a field to go back to (the photo screen opened another way), the editor only copies
        val receiver = if (ua.pysanka.ocr.PendingInsert.hasTarget()) receiver { result -> ua.pysanka.ocr.PendingInsert.set(result) } else null
        val intent = AiEditorActivity.intent(context, text.take(MAX_CHARS), AiAction.FIX_OCR, receiver, canReplace = false)
        context.startActivity(intent)
    }

    @Synchronized
    private fun open(field: Field, taken: Taken, text: String, preset: AiAction, done: AiJob.Output? = null, target: AiLanguage? = null) {
        // A new session: an answer from an editor opened earlier is not taken for this one
        val session = ++sessions
        target = Target(field, taken, session)
        answer = null
        FlorisImeService.pysankaLaunch { context ->
            AiEditorActivity.intent(
                context, text, preset, receiver { result -> set(session, result) }, canReplace = taken.mode != Mode.INSERT,
                done = done,
                target = target,
            )
        }
    }

    /** When the answer could not be put back safely, it is copied so it is not lost. */
    fun couldNotPlace(text: String) {
        ua.pysanka.privacy.Clip.copy(FlorisApplication.getHack(), text)
        toast(R.string.pysanka__ai__copied_instead)
    }

    private fun receiver(onText: (String) -> Unit) = object : ResultReceiver(Handler(Looper.getMainLooper())) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            if (resultCode != AiEditorActivity.RESULT_TEXT) return
            resultData?.getString(AiEditorActivity.EXTRA_TEXT)?.takeIf { it.isNotEmpty() }?.let(onText)
        }
    }

    @Synchronized
    private fun set(session: Long, value: String) {
        if (target?.session != session) return
        answer = value
        at = System.currentTimeMillis()
    }

    /**
     * The waiting answer if this is the field it was meant for. The first field the keyboard opens
     * after the answer decides: it is cleared either way (Pysanka's own screens do not count).
     */
    @Synchronized
    fun takeFor(info: FlorisEditorInfo, isPassword: Boolean): Result? {
        val a = answer ?: return null
        val t = target ?: return null
        if (info.packageName == FlorisApplication.getHack().packageName) return null
        answer = null
        target = null
        val fresh = System.currentTimeMillis() - at < MAX_AGE_MS
        if (!fresh || isPassword || FieldPrivacy.isPrivate) return null
        if (t.field != fieldOf(info)) {
            // Another field: not put there, but not lost either
            couldNotPlace(a)
            return null
        }
        return Result(t.taken.mode, t.taken.text, a, t.taken.start, t.taken.end)
    }
}
