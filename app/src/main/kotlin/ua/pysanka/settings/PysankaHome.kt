package ua.pysanka.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.jetpref.datastore.model.observeAsState
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import dev.patrickgold.jetpref.datastore.ui.PreferenceUiScope
import org.florisboard.lib.compose.stringRes

/**
 * Pysanka: all settings in one list, under short headings, so nothing hides behind a screen with
 * one or two options. Only what is a screen by nature opens on its own: languages, the clipboard,
 * the AI model, themes and the rare FlorisBoard details under "More".
 */
@Composable
fun PreferenceUiScope<FlorisPreferenceModel>.PysankaHome() {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val clipboardManager by context.clipboardManager()
    val history by prefs.clipboard.historyEnabled.observeAsState()
    val tags by clipboardManager.tagsFlow.collectAsState()

    Text(
        stringRes(R.string.pysanka__settings__tagline),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    PreferenceGroup(title = stringRes(R.string.pysanka__settings__languages)) {
        Preference(
            title = stringRes(R.string.pysanka__settings__languages_summary),
            onClick = { navController.navigate(Routes.Settings.Localization) },
        )
    }
    PreferenceGroup(title = stringRes(R.string.pysanka__settings__typing)) { TypingSettings() }
    PreferenceGroup(title = stringRes(R.string.pysanka__typing__swipes)) { SwipeSettings() }
    PreferenceGroup(title = stringRes(R.string.pysanka__settings__look)) { LookSettings() }
    PreferenceGroup(title = stringRes(R.string.pysanka__settings__sound)) { SoundSettings() }
    PreferenceGroup(title = stringRes(R.string.pysanka__settings__emoji)) { EmojiSettings() }
    PreferenceGroup(title = stringRes(R.string.pysanka__settings__clipboard)) {
        Preference(
            title = stringRes(R.string.pysanka__settings__clipboard_open),
            summary = if (history) {
                stringRes(R.string.pysanka__settings__clipboard_summary, "tags" to tags.size.toString())
            } else {
                stringRes(R.string.pysanka__settings__clipboard_summary_off)
            },
            onClick = { navController.navigate(Routes.Settings.Clipboard) },
        )
    }
    PreferenceGroup(title = stringRes(R.string.pysanka__settings__privacy)) { PrivacySettings() }
    PreferenceGroup(title = stringRes(R.string.pysanka__settings__ai)) {
        val aiReady = remember { ua.pysanka.ai.ModelStore(context).modelFile() != null }
        Preference(
            icon = ua.pysanka.ui.AiMark,
            title = stringRes(R.string.pysanka__settings__ai_open),
            summary = stringRes(if (aiReady) R.string.pysanka__settings__ai_ready else R.string.pysanka__settings__ai_missing),
            onClick = { navController.navigate(Routes.Settings.PysankaAi) },
        )
    }
    PreferenceGroup(title = stringRes(R.string.pysanka__settings__more)) {
        MoreSettings()
        Preference(
            title = stringRes(R.string.about__title),
            onClick = { navController.navigate(Routes.Settings.About) },
        )
    }
}
