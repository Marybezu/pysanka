package ua.pysanka.ai

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.stringRes

/**
 * Pysanka: "AI on the phone" in the settings: getting the model (the person downloads it in the
 * browser, then picks the file; only the exact published file is accepted), and removing it.
 */
@androidx.compose.runtime.Composable
fun AiSettingsScreen() = FlorisScreen {
    title = stringRes(R.string.pysanka__settings__ai)
    previewFieldVisible = false

    content {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val store = remember { ModelStore(context).also { it.cleanUp() } }
        var state by remember { mutableStateOf(store.state()) }
        var progress by remember { mutableFloatStateOf(-1f) }
        var deleteOriginal by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<Int?>(null) }

        val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            error = null
            progress = 0f
            scope.launch {
                try {
                    val r = store.import(uri, deleteOriginal) { p -> progress = p }
                    if (r.originalDeleted == false) {
                        Toast.makeText(context, R.string.pysanka__ai__original_kept, Toast.LENGTH_LONG).show()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ModelStore.WrongFile) {
                    error = R.string.pysanka__ai__wrong_file
                } catch (e: ModelStore.NoSpace) {
                    error = R.string.pysanka__ai__no_space
                } catch (e: Throwable) {
                    error = R.string.pysanka__ai__import_failed
                } finally {
                    progress = -1f
                    state = store.state()
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringRes(R.string.pysanka__ai__about), style = MaterialTheme.typography.bodyMedium)
            when (val s = state) {
                is ModelStore.State.Ready -> {
                    Text(
                        stringRes(R.string.pysanka__ai__ready, "size" to s.sizeMb.toString()),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(stringRes(R.string.pysanka__ai__how_to_use), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { store.delete(); state = store.state() }) {
                        Text(stringRes(R.string.pysanka__ai__delete))
                    }
                }
                ModelStore.State.Missing -> {
                    Text(stringRes(R.string.pysanka__ai__step1, "file" to ModelStore.E2B_FILE), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ModelStore.E2B_DOWNLOAD)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }.onFailure { Toast.makeText(context, R.string.pysanka__ai__no_browser, Toast.LENGTH_LONG).show() }
                        }) { Text(stringRes(R.string.pysanka__ai__download)) }
                        TextButton(onClick = {
                            context.getSystemService(android.content.ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("link", ModelStore.E2B_DOWNLOAD))
                        }) { Text(stringRes(R.string.pysanka__ai__copy_link)) }
                    }
                    Text(stringRes(R.string.pysanka__ai__step2), style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = deleteOriginal, onCheckedChange = { deleteOriginal = it })
                        Text(stringRes(R.string.pysanka__ai__delete_original), style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(onClick = { pick.launch(arrayOf("*/*")) }, enabled = progress < 0f) {
                        Text(stringRes(R.string.pysanka__ai__pick))
                    }
                    if (progress >= 0f) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text(stringRes(R.string.pysanka__ai__checking), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            error?.let { Text(stringRes(it, "file" to ModelStore.E2B_FILE), color = MaterialTheme.colorScheme.error) }
        }
    }
}
