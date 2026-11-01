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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.compose.stringRes

/**
 * Pysanka: "AI on the phone" in the settings: getting the model (the person downloads it in the
 * browser, then picks the file; only the exact published file is accepted), removing it, and
 * «Мій стиль» (samples of the person's own writing).
 */
@androidx.compose.runtime.Composable
fun AiSettingsScreen() = FlorisScreen {
    title = stringRes(R.string.pysanka__settings__ai)
    previewFieldVisible = false

    content {
        val context = LocalContext.current
        val store = remember { ModelStore(context).also { if (!ModelImport.running) it.cleanUp() } }
        val importStatus by ModelImport.status.collectAsState()
        var state by remember { mutableStateOf(store.state()) }
        // A copy that ended (here or while this screen was gone): read the model's state again
        androidx.compose.runtime.LaunchedEffect(importStatus.finished) {
            state = store.state()
            if (importStatus.originalKept) {
                Toast.makeText(context, R.string.pysanka__ai__original_kept, Toast.LENGTH_LONG).show()
                ModelImport.noteShown()
            }
        }
        val progress = importStatus.progress ?: -1f
        val error = importStatus.error
        // Off unless chosen: the same file may serve another app too (Tarilka uses it)
        var deleteOriginal by remember { mutableStateOf(false) }

        val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) ModelImport.start(context, uri, deleteOriginal)
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
            StyleSection()
        }
    }
}

/** «Мій стиль»: the person's own samples, added and removed only by them. */
@androidx.compose.runtime.Composable
private fun StyleSection() {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var samples by remember { mutableStateOf<List<String>?>(null) }
    var adding by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    // One change at a time: each is made from the list as it was saved
    var saving by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        samples = withContext(Dispatchers.IO) { StyleSamples.read(context) }
    }
    fun save(new: List<String>) {
        if (saving) return
        saving = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { StyleSamples.write(context, new) }.isSuccess }
            if (ok) samples = new else Toast.makeText(context, R.string.pysanka__style__save_failed, Toast.LENGTH_LONG).show()
            saving = false
        }
    }

    HorizontalDivider()
    Text(stringRes(R.string.pysanka__style__title), style = MaterialTheme.typography.titleMedium)
    Text(stringRes(R.string.pysanka__style__about), style = MaterialTheme.typography.bodyMedium)
    val list = samples ?: return
    list.forEachIndexed { i, sample ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(sample, style = MaterialTheme.typography.bodySmall, maxLines = 3, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                IconButton(onClick = { save(list.filterIndexed { j, _ -> j != i }) }, enabled = !saving) {
                    Icon(Icons.Default.Delete, contentDescription = stringRes(R.string.pysanka__style__remove))
                }
            }
        }
    }
    OutlinedButton(onClick = { draft = ""; adding = true }, enabled = list.size < StyleSamples.MAX && !saving) {
        Text(stringRes(R.string.pysanka__style__add))
    }
    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringRes(R.string.pysanka__style__add)) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = StyleSamples.cut(it) },
                    label = { Text(stringRes(R.string.pysanka__style__hint, "n" to draft.length.toString(), "max" to StyleSamples.MAX_CHARS.toString())) },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { adding = false; save(list + draft) }, enabled = draft.isNotBlank()) {
                    Text(stringRes(R.string.pysanka__style__save))
                }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringRes(R.string.action__cancel)) } },
        )
    }
}
