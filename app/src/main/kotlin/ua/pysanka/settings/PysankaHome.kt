package ua.pysanka.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.app.enumDisplayEntriesOf
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.ime.keyboard.IncognitoMode
import dev.patrickgold.florisboard.ime.media.emoji.EmojiSkinTone
import dev.patrickgold.florisboard.ime.text.gestures.SwipeAction
import dev.patrickgold.florisboard.ime.theme.ThemeMode
import dev.patrickgold.jetpref.datastore.model.PreferenceData
import dev.patrickgold.jetpref.datastore.model.observeAsState
import kotlinx.coroutines.launch
import org.florisboard.lib.android.showShortToastSync
import org.florisboard.lib.compose.stringRes
import ua.pysanka.typing.UserWords
import kotlin.reflect.KClass

/**
 * Pysanka: the settings, after Sprotyv's: one screen of blocks that open and close, each with its
 * name and what is set in the header. Only what is a screen by nature opens on its own: the
 * clipboard, the AI model, and the rare FlorisBoard details under "More".
 */
@Composable
fun PysankaHome() {
    val prefs by FlorisPreferenceStore
    val navController = LocalNavController.current
    val context = LocalContext.current
    val on = stringRes(R.string.pysanka__settings__on)
    val off = stringRes(R.string.pysanka__settings__off)
    fun state(v: Boolean) = if (v) on else off

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringRes(R.string.pysanka__settings__tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        LanguagesBlock()

        val glide by prefs.glide.enabled.observeAsState()
        val suggestions by prefs.suggestion.enabled.observeAsState()
        val autoCorrect by prefs.correction.pysankaAutoCorrect.observeAsState()
        Block(
            stringRes(R.string.pysanka__settings__typing),
            stringRes(R.string.pysanka__settings__typing_state, "glide" to state(glide), "suggestions" to state(suggestions), "correct" to state(autoCorrect)),
            icon = Icons.Outlined.Keyboard,
        ) { TypingSettings() }

        val mode by prefs.theme.mode.observeAsState()
        val numberRow by prefs.keyboard.numberRow.observeAsState()
        Block(
            stringRes(R.string.pysanka__settings__look),
            enumDisplayEntriesOf(ThemeMode::class).firstOrNull { it.key == mode }?.label?.let { theme ->
                stringRes(R.string.pysanka__settings__look_state, "theme" to theme, "numbers" to state(numberRow))
            },
            icon = Icons.Outlined.Palette,
        ) { LookSettings() }

        val history by prefs.emoji.historyEnabled.observeAsState()
        Block(
            stringRes(R.string.pysanka__settings__emoji),
            stringRes(R.string.pysanka__settings__emoji_state, "history" to state(history)),
            icon = Icons.Outlined.EmojiEmotions,
        ) { EmojiSettings() }

        val clipboardManager by context.clipboardManager()
        val clipHistory by prefs.clipboard.historyEnabled.observeAsState()
        val tags by clipboardManager.tagsFlow.collectAsState()
        val clipState = if (clipHistory) {
            stringRes(R.string.pysanka__settings__clipboard_summary, "tags" to tags.size.toString())
        } else {
            stringRes(R.string.pysanka__settings__clipboard_summary_off)
        }
        Block(stringRes(R.string.pysanka__settings__clipboard), clipState, icon = Icons.AutoMirrored.Outlined.Assignment) {
            LinkRow(stringRes(R.string.pysanka__settings__clipboard_open), clipState) { navController.navigate(Routes.Settings.Clipboard) }
        }

        val skipPasswords by prefs.clipboard.historySkipSensitive.observeAsState()
        Block(
            stringRes(R.string.pysanka__settings__privacy),
            stringRes(if (skipPasswords) R.string.pysanka__settings__privacy_state else R.string.pysanka__settings__privacy_state_keeps),
            tone = Tone.PRIVACY,
            icon = Icons.Outlined.Lock,
        ) { PrivacySettings() }

        val aiReady = remember { ua.pysanka.ai.ModelStore(context).modelFile() != null }
        val aiState = stringRes(if (aiReady) R.string.pysanka__settings__ai_ready else R.string.pysanka__settings__ai_missing)
        Block(stringRes(R.string.pysanka__settings__ai), aiState, tone = Tone.AI, icon = ua.pysanka.ui.AiMark) {
            LinkRow(stringRes(R.string.pysanka__settings__ai_open), stringRes(R.string.pysanka__settings__ai_open_hint)) {
                navController.navigate(Routes.Settings.PysankaAi)
            }
        }

        Block(stringRes(R.string.pysanka__settings__more), stringRes(R.string.pysanka__settings__more_state), icon = Icons.Outlined.Tune) {
            MoreSettings()
        }
    }
}

/** A switch bound to a setting. */
@Composable
private fun PrefToggle(pref: PreferenceData<Boolean>, title: String, hint: String? = null, enabled: Boolean = true) {
    val value by pref.observeAsState()
    val scope = rememberCoroutineScope()
    ToggleRow(title, value, hint, enabled) { scope.launch { pref.set(it) } }
}

/** A choice bound to a setting, with the names FlorisBoard gives its values. */
@Composable
private fun <T : Any> PrefChoice(pref: PreferenceData<T>, title: String, values: KClass<T>, variant: String? = null, enabled: Boolean = true) {
    val value by pref.observeAsState()
    val scope = rememberCoroutineScope()
    val entries = if (variant == null) enumDisplayEntriesOf(values) else enumDisplayEntriesOf(values, variant)
    ChoiceRow(title, entries.map { it.key to it.label }, value, enabled) { scope.launch { pref.set(it) } }
}

@Composable
private fun TypingSettings() {
    val prefs by FlorisPreferenceStore
    val context = LocalContext.current
    val glide by prefs.glide.enabled.observeAsState()
    val suggestions by prefs.suggestion.enabled.observeAsState()
    var confirmForget by remember { mutableStateOf(false) }

    PrefToggle(prefs.glide.enabled, stringRes(R.string.pysanka__typing__glide), stringRes(R.string.pysanka__typing__glide_summary))
    PrefToggle(prefs.glide.showTrail, stringRes(R.string.pysanka__typing__glide_trail), enabled = glide)
    PrefToggle(prefs.suggestion.enabled, stringRes(R.string.pysanka__typing__suggestions), stringRes(R.string.pysanka__typing__suggestions_summary))
    PrefToggle(prefs.correction.pysankaAutoCorrect, stringRes(R.string.pysanka__typing__auto_correct), stringRes(R.string.pysanka__typing__auto_correct_summary), enabled = suggestions)
    PrefToggle(prefs.correction.pysankaLearnWords, stringRes(R.string.pysanka__typing__learn_words), stringRes(R.string.pysanka__typing__learn_words_summary), enabled = suggestions)
    ActionRow(stringRes(R.string.pysanka__typing__forget_words), stringRes(R.string.pysanka__typing__forget_words_summary)) { confirmForget = true }
    BlockDivider()
    PrefToggle(prefs.correction.autoCapitalization, stringRes(R.string.pysanka__typing__auto_caps))
    PrefToggle(prefs.correction.doubleSpacePeriod, stringRes(R.string.pysanka__typing__double_space))
    PrefToggle(prefs.correction.autoSpacePunctuation, stringRes(R.string.pysanka__typing__auto_space))
    BlockDivider()
    PrefChoice(prefs.gestures.deleteKeySwipeLeft, stringRes(R.string.pysanka__typing__delete_swipe), SwipeAction::class, "deleteSwipe")
    PrefChoice(prefs.gestures.spaceBarSwipeLeft, stringRes(R.string.pysanka__typing__space_swipe_left), SwipeAction::class, "general")
    PrefChoice(prefs.gestures.spaceBarSwipeRight, stringRes(R.string.pysanka__typing__space_swipe_right), SwipeAction::class, "general")

    if (confirmForget) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(stringRes(R.string.pysanka__typing__forget_words)) },
            text = { Text(stringRes(R.string.pysanka__typing__forget_words_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    UserWords.clearAll(context)
                    context.showShortToastSync(R.string.pysanka__typing__words_forgotten)
                }) { Text(stringRes(R.string.action__yes)) }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text(stringRes(R.string.action__no)) } },
        )
    }
}

@Composable
private fun LookSettings() {
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val audio by prefs.inputFeedback.audioEnabled.observeAsState()
    PrefChoice(prefs.theme.mode, stringRes(R.string.pysanka__look__theme_mode), ThemeMode::class)
    ThemeChoices()
    PrefToggle(prefs.keyboard.numberRow, stringRes(R.string.pysanka__look__number_row))
    PrefToggle(prefs.keyboard.hintedSymbolsEnabled, stringRes(R.string.pysanka__look__key_hints), stringRes(R.string.pysanka__look__key_hints_summary))
    val fontSize by prefs.keyboard.fontSizeMultiplierPortrait.observeAsState()
    SliderRow(stringRes(R.string.pysanka__look__font_size), fontSize, 50..150, 5, { "$it %" }) {
        scope.launch { prefs.keyboard.fontSizeMultiplierPortrait.set(it) }
    }
    BlockDivider()
    PrefToggle(prefs.inputFeedback.hapticEnabled, stringRes(R.string.pysanka__sound__haptic))
    PrefToggle(prefs.inputFeedback.audioEnabled, stringRes(R.string.pysanka__sound__audio))
    val volume by prefs.inputFeedback.audioVolume.observeAsState()
    SliderRow(stringRes(R.string.pysanka__sound__volume), volume, 1..100, 1, { "$it %" }, enabled = audio) {
        scope.launch { prefs.inputFeedback.audioVolume.set(it) }
    }
}

@Composable
private fun EmojiSettings() {
    val prefs by FlorisPreferenceStore
    val navController = LocalNavController.current
    PrefChoice(prefs.emoji.preferredSkinTone, stringRes(R.string.prefs__media__emoji_preferred_skin_tone), EmojiSkinTone::class)
    PrefToggle(prefs.emoji.historyEnabled, stringRes(R.string.prefs__media__emoji_history_enabled))
    PrefToggle(prefs.emoji.suggestionEnabled, stringRes(R.string.prefs__media__emoji_suggestion_enabled))
    LinkRow(stringRes(R.string.pysanka__emoji__more), stringRes(R.string.pysanka__emoji__more_hint)) { navController.navigate(Routes.Settings.Media) }
}

@Composable
private fun PrivacySettings() {
    val prefs by FlorisPreferenceStore
    val navController = LocalNavController.current
    // What is always true, whatever the settings: shown plainly, so it can be checked
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (fact in listOf(
            R.string.pysanka__privacy__fact_offline,
            R.string.pysanka__privacy__fact_encrypted,
            R.string.pysanka__privacy__fact_backup,
            R.string.pysanka__privacy__fact_incognito,
            R.string.pysanka__privacy__fact_ocr,
        )) {
            Row {
                Icon(Icons.Default.Check, contentDescription = null, tint = Tone.PRIVACY.tint().ink)
                Spacer(Modifier.width(10.dp))
                Text(stringRes(fact), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    BlockDivider()
    PrefToggle(prefs.clipboard.historySkipSensitive, stringRes(R.string.pysanka__clipboard__skip_sensitive__label), stringRes(R.string.pysanka__clipboard__skip_sensitive__summary))
    PrefToggle(prefs.clipboard.cleanLinks, stringRes(R.string.pysanka__links__clean), stringRes(R.string.pysanka__links__clean_summary))
    PrefToggle(prefs.pysankaOcr.blockScreenshots, stringRes(R.string.pysanka__privacy__block_screenshots), stringRes(R.string.pysanka__privacy__block_screenshots_summary))
    PrefChoice(prefs.suggestion.incognitoMode, stringRes(R.string.pysanka__privacy__incognito), IncognitoMode::class)
    LinkRow(stringRes(R.string.pysanka__clipboard_copy__title), stringRes(R.string.pysanka__clipboard_copy__summary)) {
        navController.navigate(Routes.Settings.ClipboardCopy)
    }
}

@Composable
private fun MoreSettings() {
    val navController = LocalNavController.current
    LinkRow(stringRes(R.string.settings__smartbar__title), stringRes(R.string.pysanka__more__smartbar_summary)) { navController.navigate(Routes.Settings.Smartbar) }
    LinkRow(stringRes(R.string.pysanka__typing__more_gestures), stringRes(R.string.pysanka__typing__more_gestures_summary)) { navController.navigate(Routes.Settings.Gestures) }
    LinkRow(stringRes(R.string.pysanka__look__more_keyboard), stringRes(R.string.pysanka__look__more_keyboard_summary)) { navController.navigate(Routes.Settings.Keyboard) }
    LinkRow(stringRes(R.string.pysanka__typing__more_typing), stringRes(R.string.pysanka__typing__more_typing_summary)) { navController.navigate(Routes.Settings.Typing) }
    LinkRow(stringRes(R.string.physical_keyboard__title)) { navController.navigate(Routes.Settings.PhysicalKeyboard) }
    LinkRow(stringRes(R.string.settings__other__title)) { navController.navigate(Routes.Settings.Other) }
    LinkRow(stringRes(R.string.about__title)) { navController.navigate(Routes.Settings.About) }
}
