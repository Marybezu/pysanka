package ua.pysanka.ai

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.AppTheme
import dev.patrickgold.florisboard.app.apptheme.FlorisAppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.ProvideLocalizedResources
import org.florisboard.lib.compose.stringRes

/**
 * Pysanka: the AI editor. Runs in its own process (":ai"; see AndroidManifest.xml): the model
 * needs about 2.6 GB and must not weigh on the keyboard. This process reads no settings and
 * touches no stored data: it gets the text in the intent and gives the answer back through the
 * [ResultReceiver] it was handed, or copies it. Nothing is written anywhere.
 */
class AiEditorActivity : ComponentActivity() {
    companion object {
        const val RESULT_TEXT = 1
        const val EXTRA_TEXT = "text"
        private const val EXTRA_PRESET = "preset"
        private const val EXTRA_RECEIVER = "receiver"
        private const val EXTRA_CAN_REPLACE = "can_replace"
        private const val EXTRA_SECURE = "secure"

        fun intent(context: Context, text: String, preset: AiAction?, receiver: ResultReceiver, canReplace: Boolean): Intent {
            val secure = runCatching {
                val prefs by dev.patrickgold.florisboard.app.FlorisPreferenceStore
                prefs.pysankaOcr.blockScreenshots.get()
            }.getOrDefault(false)
            return Intent(context, AiEditorActivity::class.java)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_PRESET, preset?.name)
                .putExtra(EXTRA_RECEIVER, receiver)
                .putExtra(EXTRA_CAN_REPLACE, canReplace)
                .putExtra(EXTRA_SECURE, secure)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The keyboard's setting, passed along (this process does not read settings)
        if (intent.getBooleanExtra(EXTRA_SECURE, false)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty().take(AiBridge.MAX_CHARS)
        val preset = intent.getStringExtra(EXTRA_PRESET)?.let { n -> AiAction.entries.firstOrNull { it.name == n } }
        @Suppress("DEPRECATION")
        val receiver: ResultReceiver? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RECEIVER, ResultReceiver::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RECEIVER)
        }
        val canReplace = intent.getBooleanExtra(EXTRA_CAN_REPLACE, false)
        setContent {
            ProvideLocalizedResources(this, appName = R.string.app_name) {
                FlorisAppTheme(theme = AppTheme.AUTO) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        AiEditorScreen(
                            original = text,
                            preset = preset,
                            replaceLabel = if (receiver == null) null else if (canReplace) R.string.pysanka__ai__replace else R.string.pysanka__ai__insert,
                            onUse = { result ->
                                receiver?.send(RESULT_TEXT, Bundle().apply { putString(EXTRA_TEXT, result) })
                                finish()
                            },
                            close = { finish() },
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // The model takes gigabytes; this process exists only for the editor
        if (isFinishing) CoroutineScope(Dispatchers.Default).launch { AiEngine.release() }
    }
}

private sealed interface AiStage {
    data object Idle : AiStage
    data object Working : AiStage
    data class Done(val text: String) : AiStage
    data class Failed(val reason: String) : AiStage
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiEditorScreen(original: String, preset: AiAction?, replaceLabel: Int?, onUse: (String) -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed = remember { ModelStore(context).modelFile() != null }
    var source by rememberSaveable { mutableStateOf(original) }
    var action by rememberSaveable { mutableStateOf(preset ?: AiAction.FIX) }
    var language by rememberSaveable { mutableStateOf(AiLanguage.EN) }
    var stage by remember { mutableStateOf<AiStage>(AiStage.Idle) }
    var result by rememberSaveable { mutableStateOf("") }

    fun run() {
        if (source.isBlank()) return
        stage = AiStage.Working
        scope.launch {
            stage = try {
                val out = AiEngine.run(context, action, source.take(AiBridge.MAX_CHARS), language)
                result = out
                if (out.isBlank()) AiStage.Failed("empty") else AiStage.Done(out)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AiStage.Failed(e.javaClass.simpleName)
            }
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringRes(R.string.pysanka__ai__title), style = MaterialTheme.typography.titleLarge)
        if (!installed) {
            Text(stringRes(R.string.pysanka__ai__no_model), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { openAiSettings(context); close() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringRes(R.string.pysanka__ai__open_settings))
            }
            TextButton(onClick = close) { Text(stringRes(R.string.pysanka__ocr__close)) }
            return@Column
        }
        OutlinedTextField(
            value = source,
            onValueChange = { source = it.take(AiBridge.MAX_CHARS); if (stage is AiStage.Done) stage = AiStage.Idle },
            label = { Text(stringRes(R.string.pysanka__ai__source)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 260.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (a in AiAction.entries) {
                FilterChip(selected = a == action, onClick = { action = a }, label = { Text(stringRes(a.label)) })
            }
        }
        if (action == AiAction.TRANSLATE) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (l in AiLanguage.entries) {
                    FilterChip(selected = l == language, onClick = { language = l }, label = { Text(stringRes(l.label)) })
                }
            }
        }
        Button(onClick = { run() }, enabled = stage !is AiStage.Working && source.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(stringRes(R.string.pysanka__ai__run))
        }
        when (val s = stage) {
            AiStage.Idle -> {}
            AiStage.Working -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text(
                    stringRes(if (AiEngine.isLoaded) R.string.pysanka__ai__working else R.string.pysanka__ai__loading),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            is AiStage.Failed -> {
                Text(stringRes(R.string.pysanka__ai__failed), style = MaterialTheme.typography.bodyMedium)
                // Only the kind of error, so it can be reported
                Text(s.reason, style = MaterialTheme.typography.bodySmall)
            }
            is AiStage.Done -> {
                OutlinedTextField(
                    value = result,
                    onValueChange = { result = it },
                    label = { Text(stringRes(R.string.pysanka__ai__result)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (replaceLabel != null) {
                        Button(onClick = { onUse(result) }, enabled = result.isNotBlank(), modifier = Modifier.weight(1f)) {
                            Text(stringRes(replaceLabel))
                        }
                    }
                    OutlinedButton(onClick = { copy(context, result) }, enabled = result.isNotBlank(), modifier = Modifier.weight(1f)) {
                        Text(stringRes(R.string.pysanka__ocr__copy))
                    }
                }
            }
        }
        TextButton(onClick = close) { Text(stringRes(R.string.pysanka__ocr__close)) }
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Pysanka", text))
}

/** Opens the AI settings (model download) in Pysanka's settings. */
fun openAiSettings(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("ui://florisboard/settings/pysanka/ai")).apply {
        setPackage(context.packageName)
        addCategory(Intent.CATEGORY_BROWSABLE)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    runCatching { context.startActivity(intent) }
}
