package ua.pysanka.ai

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Pysanka: the AI editor from other apps, without the keyboard:
 * - "Share" → "Pysanka AI" with any text (an article, a long message, a document): opens the
 *   editor with "Summary" chosen; the answer can be copied.
 * - The text selection menu → "Pysanka AI": opens the editor with "Grammar" chosen; if the app
 *   allows it, "Replace" puts the answer in place of the selection.
 *
 * This small screen is in the keyboard's process (so it can pass the "block screenshots" choice
 * along) and shows nothing itself; the editor runs in its own process as always. Any app may send
 * text here, so only plain text is taken, and no more than [AiJob.HARD_MAX] characters.
 */
class AiEntryActivity : ComponentActivity() {
    private val editor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringExtra(AiEditorActivity.EXTRA_TEXT)
        if (intent.action == Intent.ACTION_PROCESS_TEXT && r.resultCode == RESULT_OK && text != null) {
            setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, text))
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Already waiting for the editor (this screen was only recreated)
        if (savedInstanceState != null) return
        val text: String
        val preset: AiAction
        var canReplace = false
        when (intent.action) {
            Intent.ACTION_SEND -> {
                text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
                preset = AiAction.SUMMARY
            }
            Intent.ACTION_PROCESS_TEXT -> {
                text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
                preset = AiAction.FIX
                canReplace = !intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
            }
            else -> {
                finish()
                return
            }
        }
        if (text.isBlank()) {
            finish()
            return
        }
        editor.launch(
            AiEditorActivity.intent(this, text.take(AiJob.HARD_MAX), preset, receiver = null, canReplace = canReplace, forResult = true),
        )
    }
}
