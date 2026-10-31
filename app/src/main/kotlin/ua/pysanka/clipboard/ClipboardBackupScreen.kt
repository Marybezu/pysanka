package ua.pysanka.clipboard

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.stringRes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pysanka: export and import of the clipboard history (text and images).
 * An export is always protected with a password; import also reads a FlorisBoard backup.
 */
@Composable
fun ClipboardBackupScreen() = FlorisScreen {
    title = stringRes(R.string.pysanka__clipboard_copy__title)
    previewFieldVisible = false

    content {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }

        // ------------------------------------------------------------ export
        var text by remember { mutableStateOf(true) }
        var images by remember { mutableStateOf(true) }
        var pinnedOnly by remember { mutableStateOf(false) }
        var password by remember { mutableStateOf("") }
        var repeat by remember { mutableStateOf("") }
        var showPassword by remember { mutableStateOf(false) }

        val msgExported = stringRes(R.string.pysanka__clipboard_copy__exported)
        val msgFailed = stringRes(R.string.pysanka__clipboard_copy__failed)
        val msgWrongPassword = stringRes(R.string.pysanka__clipboard_copy__wrong_password)
        val msgImported = stringRes(R.string.pysanka__clipboard_copy__imported)
        val msgNotCopy = stringRes(R.string.pysanka__clipboard_copy__not_a_copy)

        val exportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream"),
        ) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            val chars = password.toCharArray()
            busy = true
            message = null
            scope.launch {
                message = try {
                    val n = ClipboardBackup.export(context, uri, chars, ClipboardBackup.Options(text, images, pinnedOnly))
                    password = ""
                    repeat = ""
                    msgExported.replace("{n}", n.toString())
                } catch (e: Exception) {
                    // A half-written file is useless and could confuse later: remove it
                    runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    msgFailed
                } finally {
                    chars.fill(' ')
                    busy = false
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringRes(R.string.pysanka__clipboard_copy__export), style = MaterialTheme.typography.titleMedium)
            Text(stringRes(R.string.pysanka__clipboard_copy__export_hint), style = MaterialTheme.typography.bodyMedium)
            CheckRow(stringRes(R.string.pysanka__clipboard_copy__text), text) { text = it }
            CheckRow(stringRes(R.string.pysanka__clipboard_copy__images), images) { images = it }
            CheckRow(stringRes(R.string.pysanka__clipboard_copy__pinned_only), pinnedOnly) { pinnedOnly = it }
            val visual = if (showPassword) VisualTransformation.None else PasswordVisualTransformation()
            val kb = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
            OutlinedTextField(
                value = password, onValueChange = { password = it }, singleLine = true,
                label = { Text(stringRes(R.string.pysanka__clipboard_copy__password)) },
                visualTransformation = visual, keyboardOptions = kb, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = repeat, onValueChange = { repeat = it }, singleLine = true,
                label = { Text(stringRes(R.string.pysanka__clipboard_copy__repeat)) },
                visualTransformation = visual, keyboardOptions = kb, modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { showPassword = !showPassword }) {
                Text(stringRes(if (showPassword) R.string.pysanka__clipboard_copy__hide else R.string.pysanka__clipboard_copy__show))
            }
            val tooShort = password.length < ClipboardBackup.MIN_PASSWORD_LENGTH
            val mismatch = repeat.isNotEmpty() && repeat != password
            when {
                password.isNotEmpty() && tooShort -> Hint(
                    stringRes(R.string.pysanka__clipboard_copy__too_short).replace("{n}", ClipboardBackup.MIN_PASSWORD_LENGTH.toString()),
                )
                mismatch -> Hint(stringRes(R.string.pysanka__clipboard_copy__mismatch))
            }
            Button(
                enabled = !busy && (text || images) && !tooShort && repeat == password,
                onClick = {
                    val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
                    exportLauncher.launch("pysanka-clipboard-$date.${ClipboardBackup.FILE_EXTENSION}")
                },
            ) { Text(stringRes(R.string.pysanka__clipboard_copy__export_button)) }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // -------------------------------------------------------- import
            var picked by remember { mutableStateOf<Uri?>(null) }
            var pickedKind by remember { mutableStateOf<ClipboardBackup.Kind?>(null) }
            var importPassword by remember { mutableStateOf("") }

            fun runImport(uri: Uri, pw: CharArray?) {
                busy = true
                message = null
                scope.launch {
                    message = try {
                        val r = ClipboardBackup.import(context, uri, pw)
                        picked = null
                        pickedKind = null
                        importPassword = ""
                        msgImported.replace("{added}", r.added.toString()).replace("{skipped}", r.skipped.toString())
                    } catch (e: ClipboardBackup.WrongPasswordOrDamaged) {
                        msgWrongPassword
                    } catch (e: Exception) {
                        msgFailed
                    } finally {
                        pw?.fill(' ')
                        busy = false
                    }
                }
            }

            val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                if (uri == null) return@rememberLauncherForActivityResult
                message = null
                when (ClipboardBackup.detect(context, uri)) {
                    // A FlorisBoard backup has no password: import right away
                    ClipboardBackup.Kind.FLORISBOARD -> runImport(uri, null)
                    ClipboardBackup.Kind.PYSANKA -> {
                        picked = uri
                        pickedKind = ClipboardBackup.Kind.PYSANKA
                    }
                    ClipboardBackup.Kind.UNKNOWN -> message = msgNotCopy
                }
            }

            Text(stringRes(R.string.pysanka__clipboard_copy__import), style = MaterialTheme.typography.titleMedium)
            Text(stringRes(R.string.pysanka__clipboard_copy__import_hint), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(enabled = !busy, onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                Text(stringRes(R.string.pysanka__clipboard_copy__pick_file))
            }
            if (pickedKind == ClipboardBackup.Kind.PYSANKA && picked != null) {
                OutlinedTextField(
                    value = importPassword, onValueChange = { importPassword = it }, singleLine = true,
                    label = { Text(stringRes(R.string.pysanka__clipboard_copy__password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(enabled = !busy && importPassword.isNotEmpty(), onClick = { runImport(picked!!, importPassword.toCharArray()) }) {
                    Text(stringRes(R.string.pysanka__clipboard_copy__import_button))
                }
            }

            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                    Text(stringRes(R.string.pysanka__clipboard_copy__working))
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}
