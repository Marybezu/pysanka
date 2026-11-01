package ua.pysanka.ai

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
 * [ResultReceiver] it was handed (or as its result), or copies it. No text is written anywhere; only
 * the model's cache and two numbers of measured speed (see [AiJob]).
 */
class AiEditorActivity : ComponentActivity() {
    companion object {
        const val RESULT_TEXT = 1
        const val EXTRA_TEXT = "text"
        private const val EXTRA_PRESET = "preset"
        private const val EXTRA_RECEIVER = "receiver"
        private const val EXTRA_CAN_REPLACE = "can_replace"
        private const val EXTRA_SECURE = "secure"

        private const val EXTRA_FOR_RESULT = "for_result"
        private const val EXTRA_LANGUAGE = "language"
        private const val EXTRA_DONE = "done"

        /**
         * [receiver]: where the answer goes back to the keyboard; or [forResult]: the answer is
         * this screen's result (for Pysanka's own entry from the selection menu).
         */
        fun intent(
            context: Context, text: String, preset: AiAction?, receiver: ResultReceiver?, canReplace: Boolean,
            forResult: Boolean = false,
            /** From "Share": opened on top, an editor already open from the keyboard stays. */
            alongside: Boolean = false,
            /** An answer the keyboard's panel already has: shown at once, not asked for again. */
            done: AiJob.Output? = null,
        ): Intent {
            val prefs by dev.patrickgold.florisboard.app.FlorisPreferenceStore
            val secure = runCatching { prefs.pysankaOcr.blockScreenshots.get() }.getOrDefault(false)
            val language = runCatching { prefs.other.settingsLanguage.get() }.getOrDefault("auto")
            return Intent(context, AiEditorActivity::class.java)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_PRESET, preset?.name)
                .putExtra(EXTRA_RECEIVER, receiver)
                .putExtra(EXTRA_CAN_REPLACE, canReplace)
                .putExtra(EXTRA_SECURE, secure)
                .putExtra(EXTRA_FOR_RESULT, forResult)
                .putExtra(EXTRA_LANGUAGE, language)
                .apply {
                    if (done != null) {
                        putExtra(EXTRA_DONE, Bundle().apply {
                            putString("text", done.text)
                            putInt("removed", done.removed)
                            putBoolean("unconfirmed", done.unconfirmed)
                            putInt("seconds", done.seconds)
                            putString("backend", done.backend)
                        })
                    }
                }
                // A result comes back only within the same task
                .apply {
                    when {
                        forResult -> {}
                        alongside -> addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        else -> addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AiEngine.screenOpened()
        // The keyboard's setting, passed along (this process does not read settings)
        if (intent.getBooleanExtra(EXTRA_SECURE, false)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty().take(AiJob.HARD_MAX)
        val preset = intent.getStringExtra(EXTRA_PRESET)?.let { n -> AiAction.entries.firstOrNull { it.name == n } }
        val receiver: ResultReceiver? = androidx.core.content.IntentCompat.getParcelableExtra(intent, EXTRA_RECEIVER, ResultReceiver::class.java)
        val canReplace = intent.getBooleanExtra(EXTRA_CAN_REPLACE, false)
        val forResult = intent.getBooleanExtra(EXTRA_FOR_RESULT, false) && callingActivity?.packageName == packageName
        val done = intent.getBundleExtra(EXTRA_DONE)?.let { b ->
            b.getString("text")?.takeIf { it.isNotBlank() }?.let {
                AiJob.Output(it.take(AiJob.HARD_MAX), b.getInt("removed"), b.getBoolean("unconfirmed"), b.getInt("seconds"), b.getString("backend").orEmpty())
            }
        }
        setContent {
            ProvideLocalizedResources(ua.pysanka.settings.AppLanguage.context(this, intent.getStringExtra(EXTRA_LANGUAGE)), appName = R.string.app_name) {
                FlorisAppTheme(theme = AppTheme.AUTO) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        AiEditorScreen(
                            original = text,
                            preset = preset,
                            done = done,
                            replaceLabel = when {
                                forResult -> if (canReplace) R.string.pysanka__ai__replace else null
                                receiver == null -> null
                                canReplace -> R.string.pysanka__ai__replace
                                else -> R.string.pysanka__ai__insert
                            },
                            onUse = { result ->
                                if (forResult) {
                                    setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXT, result))
                                } else {
                                    receiver?.send(RESULT_TEXT, Bundle().apply { putString(EXTRA_TEXT, result) })
                                }
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
        // The last editor screen gone (a new one may already be open): stop a text being written
        // (it would keep the processor busy), then free the memory
        if (AiEngine.screenClosed() && isFinishing) {
            AiEngine.cancelAll()
            CoroutineScope(Dispatchers.Default).launch { if (AiEngine.screenClosedStill()) AiEngine.release() }
        }
    }
}

private sealed interface AiStage {
    data object Idle : AiStage
    /** Longer than fits in the time budget (or than a whole-text action takes). */
    data class TooLong(val limit: Int, val whole: Boolean) : AiStage
    data class Working(val part: Int, val parts: Int) : AiStage
    data class Done(val output: AiJob.Output) : AiStage
    data class Failed(val reason: String) : AiStage
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiEditorScreen(
    original: String,
    preset: AiAction?,
    done: AiJob.Output?,
    replaceLabel: Int?,
    onUse: (String) -> Unit,
    close: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed = remember { ModelStore(context).modelFile() != null }
    val hasStyle = remember { StyleSamples.has(context) }
    // The texts stay in memory only (up to 60 000 characters would be too much for saved state);
    // turning the phone does not recreate this screen
    var source by remember { mutableStateOf(original) }
    var action by rememberSaveable { mutableStateOf(preset ?: AiAction.FIX) }
    var language by rememberSaveable { mutableStateOf(AiLanguage.EN) }
    var stage by remember { mutableStateOf<AiStage>(if (done != null) AiStage.Done(done) else AiStage.Idle) }
    var result by remember { mutableStateOf(done?.text.orEmpty()) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var run by remember { mutableStateOf(0L) }
    // Start loading the model while the person chooses what to do
    if (installed) androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { AiEngine.warmUp(context) } }

    fun start(text: String) {
        stage = AiStage.Working(1, 1)
        val thisRun = AiEngine.newRun()
        run = thisRun
        job = scope.launch {
            stage = try {
                val out = AiJob.run(context, thisRun, action, text, language) { part, parts -> stage = AiStage.Working(part, parts) }
                result = out.text
                if (out.text.isBlank()) AiStage.Failed("empty") else AiStage.Done(out)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AiStage.Failed(e.javaClass.simpleName)
            } finally {
                AiEngine.endRun(thisRun)
            }
        }
    }

    fun run() {
        if (source.isBlank()) return
        val limit = AiJob.limit(context, action)
        if (source.length > limit) {
            stage = AiStage.TooLong(limit, whole = action.kind == AiAction.Kind.WHOLE || action.kind == AiAction.Kind.ADVICE)
            return
        }
        start(source)
    }

    fun stop() {
        AiEngine.cancel(run)
        job?.cancel()
        job = null
        stage = AiStage.Idle
    }

    fun thousands(n: Int) = ((n + 500) / 1000).toString()

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
        val busy = stage is AiStage.Working
        OutlinedTextField(
            value = source,
            onValueChange = { source = it.take(AiJob.HARD_MAX); if (stage !is AiStage.Working) stage = AiStage.Idle },
            enabled = !busy,
            label = { Text(stringRes(R.string.pysanka__ai__source_count, "n" to source.length.toString())) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 260.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (a in AiAction.entries.filter { it != AiAction.MY_STYLE || hasStyle }) {
                FilterChip(
                    selected = a == action, enabled = !busy,
                    onClick = { action = a; if (stage !is AiStage.Working) stage = AiStage.Idle },
                    label = { Text(stringRes(a.label)) },
                )
            }
        }
        if (action == AiAction.TRANSLATE) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (l in AiLanguage.entries) {
                    FilterChip(selected = l == language, enabled = !busy, onClick = { language = l }, label = { Text(stringRes(l.label)) })
                }
            }
        }
        if (busy) {
            OutlinedButton(onClick = { stop() }, modifier = Modifier.fillMaxWidth()) { Text(stringRes(R.string.pysanka__ai__stop)) }
        } else {
            Button(onClick = { run() }, enabled = source.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(stringRes(R.string.pysanka__ai__run))
            }
        }
        when (val s = stage) {
            AiStage.Idle -> {}
            is AiStage.TooLong -> {
                Text(
                    stringRes(
                        if (s.whole) R.string.pysanka__ai__too_long_whole else R.string.pysanka__ai__too_long_time,
                        "n" to thousands(source.length), "limit" to thousands(s.limit),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val first = TextChunks.prefix(source, s.limit)
                Button(onClick = { start(first) }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringRes(
                            R.string.pysanka__ai__take_first,
                            "n" to thousands(first.length), "s" to AiJob.estimate(context, action, first.length).toString(),
                        ),
                    )
                }
                TextButton(onClick = { stage = AiStage.Idle }) { Text(stringRes(R.string.pysanka__ai__cancel)) }
            }
            is AiStage.Working -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text(
                    when {
                        !AiEngine.loaded -> stringRes(R.string.pysanka__ai__loading)
                        s.parts > 1 -> stringRes(R.string.pysanka__ai__working_part, "k" to s.part.toString(), "n" to s.parts.toString())
                        else -> stringRes(R.string.pysanka__ai__working)
                    },
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
                Text(
                    stringRes(R.string.pysanka__ai__done_in, "s" to s.output.seconds.toString(), "where" to s.output.backend),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (s.output.removed > 0 && !s.output.unconfirmed) {
                    Text(stringRes(R.string.pysanka__ai__fact_removed, "n" to s.output.removed.toString()), style = MaterialTheme.typography.bodySmall)
                }
                if (s.output.unconfirmed) {
                    Text(stringRes(R.string.pysanka__ai__fact_unconfirmed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (replaceLabel != null && action.replaces) {
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

private fun copy(context: Context, text: String) = ua.pysanka.privacy.Clip.copy(context, text)

/** Opens the AI settings (model download) in Pysanka's settings. */
fun openAiSettings(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("ui://pysanka/settings/pysanka/ai")).apply {
        setPackage(context.packageName)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    runCatching { context.startActivity(intent) }
}
