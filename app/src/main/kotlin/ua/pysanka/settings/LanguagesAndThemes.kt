package ua.pysanka.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.extensionManager
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.lib.ext.ExtensionComponentName
import dev.patrickgold.florisboard.subtypeManager
import dev.patrickgold.jetpref.datastore.model.observeAsState
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.stringRes

/**
 * Pysanka: the keyboard's languages, simply: the ones in use, removed with a tap, and "Add a
 * language" with the ready-made ones (each comes with its usual layout); the globe key switches.
 */
@Composable
fun LanguagesBlock() {
    val context = LocalContext.current
    val subtypeManager by context.subtypeManager()
    val keyboardManager by context.keyboardManager()
    val subtypes by subtypeManager.subtypesFlow.collectAsState()
    val presets by keyboardManager.resources.subtypePresets.collectAsState()
    var adding by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Subtype?>(null) }

    Block(
        stringRes(R.string.pysanka__settings__languages),
        subtypes.joinToString { it.primaryLocale.displayName() },
        icon = Icons.Default.Language,
    ) {
        for (subtype in subtypes) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(subtype.primaryLocale.displayName(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                // The last language stays: a keyboard needs one
                if (subtypes.size > 1) {
                    IconButton(onClick = { removing = subtype }) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringRes(R.string.pysanka__languages__remove))
                    }
                }
            }
        }
        ActionRow(stringRes(R.string.pysanka__languages__add)) { adding = true }
        BlockNote(stringRes(R.string.pysanka__languages__switch_hint))
    }

    if (adding) {
        val choices = remember(presets, subtypes) {
            presets.filter { p -> subtypes.none { it.primaryLocale == p.locale } }
                .sortedWith(compareBy({ PopularLanguages.rank(it.locale.languageTag()) }, { it.locale.displayName() }))
        }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringRes(R.string.pysanka__languages__add)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(choices, key = { it.locale.languageTag() }) { preset ->
                        Text(
                            preset.locale.displayName(),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .clickable { adding = false; subtypeManager.addSubtype(preset.toSubtype()) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringRes(R.string.action__cancel)) } },
        )
    }
    removing?.let { subtype ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringRes(R.string.pysanka__languages__remove_confirm, "name" to subtype.primaryLocale.displayName())) },
            confirmButton = {
                TextButton(onClick = { removing = null; subtypeManager.removeSubtype(subtype) }) { Text(stringRes(R.string.action__yes)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringRes(R.string.action__no)) } },
        )
    }
}

/** The day and the night theme, from the themes that come with the keyboard. */
@Composable
fun ThemeChoices() {
    val prefs by FlorisPreferenceStore
    val context = LocalContext.current
    val extensionManager by context.extensionManager()
    val scope = rememberCoroutineScope()
    val extensions by extensionManager.themes.collectAsState()
    val themes = remember(extensions) {
        extensions.flatMap { ext -> ext.themes.map { ExtensionComponentName(ext.meta.id, it.id) to it } }.sortedBy { it.second.label }
    }
    val day by prefs.theme.dayThemeId.observeAsState()
    val night by prefs.theme.nightThemeId.observeAsState()
    ChoiceRow(stringRes(R.string.pysanka__look__day_theme), themes.filter { !it.second.isNightTheme }.map { it.first to it.second.label }, day) {
        scope.launch { prefs.theme.dayThemeId.set(it) }
    }
    ChoiceRow(stringRes(R.string.pysanka__look__night_theme), themes.filter { it.second.isNightTheme }.map { it.first to it.second.label }, night) {
        scope.launch { prefs.theme.nightThemeId.set(it) }
    }
}
