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
import dev.patrickgold.florisboard.ime.keyboard3.ImeEditor
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
    /** The longest text the editor takes (the small model handles about this much well). */
    const val MAX_CHARS = 4000

    private const val MAX_AGE_MS = 10 * 60 * 1000L

    /** What the answer replaces: the selection, the whole text, or nothing (it goes in at the cursor). */
    enum class Mode { SELECTION, ALL, INSERT }

    data class Taken(val mode: Mode, val text: String)

    data class Result(val mode: Mode, val original: String, val text: String)

    private data class Target(val packageName: String?, val fieldId: Int, val mode: Mode, val original: String)

    private var target: Target? = null
    private var answer: String? = null
    private var at = 0L

    private fun toast(res: Int) {
        val app = FlorisApplication.getHack()
        Toast.makeText(app, res, Toast.LENGTH_LONG).show()
    }

    /** The "AI" button on the keyboard. */
    fun openFromKeyboard(editor: ImeEditor) {
        if (FieldPrivacy.isPassword || FieldPrivacy.isPrivate) {
            toast(R.string.pysanka__ai__not_here)
            return
        }
        val taken = editor.pysankaTextForAi(MAX_CHARS) ?: return
        if (taken.text.length > MAX_CHARS) {
            toast(R.string.pysanka__ai__too_long)
            return
        }
        val info = editor.info
        open(Target(info.packageName, info.base.fieldId, taken.mode, taken.text), preset = null)
    }

    /**
     * From "text from a photo": the recognised [text] goes to the editor (with "fix recognised
     * text" chosen), and its answer goes in at the cursor of the field the photo screen was opened
     * from (that screen remembered it in [ua.pysanka.ocr.PendingInsert]).
     */
    fun openFromPhoto(context: Context, text: String) {
        val intent = AiEditorActivity.intent(context, text.take(MAX_CHARS), AiAction.FIX_OCR, receiver { result ->
            ua.pysanka.ocr.PendingInsert.set(result)
        }, canReplace = false)
        context.startActivity(intent)
    }

    @Synchronized
    private fun open(t: Target, preset: AiAction?) {
        target = t
        answer = null
        FlorisImeService.pysankaLaunch { context ->
            AiEditorActivity.intent(context, t.original, preset, receiver { result -> set(result) }, canReplace = t.mode != Mode.INSERT)
        }
    }

    private fun receiver(onText: (String) -> Unit) = object : ResultReceiver(Handler(Looper.getMainLooper())) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            if (resultCode != AiEditorActivity.RESULT_TEXT) return
            resultData?.getString(AiEditorActivity.EXTRA_TEXT)?.takeIf { it.isNotEmpty() }?.let(onText)
        }
    }

    @Synchronized
    private fun set(value: String) {
        if (target == null) return
        answer = value
        at = System.currentTimeMillis()
    }

    /** The waiting answer if this is the field it was meant for; it is cleared either way. */
    @Synchronized
    fun takeFor(packageName: String?, fieldId: Int, isPassword: Boolean): Result? {
        val a = answer ?: return null
        val t = target ?: return null
        answer = null
        target = null
        val fresh = System.currentTimeMillis() - at < MAX_AGE_MS
        if (!fresh || isPassword || t.packageName != packageName || t.fieldId != fieldId) return null
        return Result(t.mode, t.original, a)
    }
}
