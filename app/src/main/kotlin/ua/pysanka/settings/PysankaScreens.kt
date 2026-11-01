package ua.pysanka.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.app.enumDisplayEntriesOf
import dev.patrickgold.florisboard.ime.keyboard.IncognitoMode
import dev.patrickgold.florisboard.ime.text.gestures.SwipeAction
import dev.patrickgold.florisboard.ime.theme.PreferredThemeMode
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.ui.DialogSliderPreference
import dev.patrickgold.jetpref.datastore.ui.ListPreference
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import org.florisboard.lib.compose.stringRes

/**
 * Pysanka: the short settings screens. Each shows the few settings most people change; the full
 * FlorisBoard screens stay reachable under "More" for everything rarer.
 */

@Composable
fun PysankaTypingScreen() = FlorisScreen {
    title = stringRes(R.string.pysanka__settings__typing)
    previewFieldVisible = true
    val navController = LocalNavController.current

    content {
        SwitchPreference(
            prefs.glide.enabled,
            title = stringRes(R.string.pysanka__typing__glide),
            summary = stringRes(R.string.pysanka__typing__glide_summary),
        )
        SwitchPreference(
            prefs.glide.showTrail,
            title = stringRes(R.string.pysanka__typing__glide_trail),
            enabledIf = { prefs.glide.enabled isEqualTo true },
        )
        SwitchPreference(
            prefs.suggestion.enabled,
            title = stringRes(R.string.pysanka__typing__suggestions),
            summary = stringRes(R.string.pysanka__typing__suggestions_summary),
        )
        SwitchPreference(
            prefs.typing.autoCapitalization,
            title = stringRes(R.string.pysanka__typing__auto_caps),
        )
        SwitchPreference(
            prefs.correction.doubleSpacePeriod,
            title = stringRes(R.string.pysanka__typing__double_space),
        )
        SwitchPreference(
            prefs.correction.autoSpacePunctuation,
            title = stringRes(R.string.pysanka__typing__auto_space),
        )
        PreferenceGroup(title = stringRes(R.string.pysanka__typing__swipes)) {
            ListPreference(
                prefs.gestures.deleteKeySwipeLeft,
                title = stringRes(R.string.pysanka__typing__delete_swipe),
                entries = enumDisplayEntriesOf(SwipeAction::class, "deleteSwipe"),
            )
            ListPreference(
                prefs.gestures.spaceBarSwipeLeft,
                title = stringRes(R.string.pysanka__typing__space_swipe_left),
                entries = enumDisplayEntriesOf(SwipeAction::class, "general"),
            )
            ListPreference(
                prefs.gestures.spaceBarSwipeRight,
                title = stringRes(R.string.pysanka__typing__space_swipe_right),
                entries = enumDisplayEntriesOf(SwipeAction::class, "general"),
            )
        }
        PreferenceGroup(title = stringRes(R.string.pysanka__settings__more)) {
            Preference(
                title = stringRes(R.string.pysanka__typing__more_typing),
                summary = stringRes(R.string.pysanka__typing__more_typing_summary),
                onClick = { navController.navigate(Routes.Settings.Typing) },
            )
            Preference(
                title = stringRes(R.string.pysanka__typing__more_gestures),
                summary = stringRes(R.string.pysanka__typing__more_gestures_summary),
                onClick = { navController.navigate(Routes.Settings.Gestures) },
            )
        }
    }
}

@Composable
fun PysankaLookScreen() = FlorisScreen {
    title = stringRes(R.string.pysanka__settings__look)
    previewFieldVisible = true
    val navController = LocalNavController.current

    content {
        ListPreference(
            prefs.theme.mode,
            title = stringRes(R.string.pysanka__look__theme_mode),
            entries = enumDisplayEntriesOf(PreferredThemeMode::class),
        )
        Preference(
            title = stringRes(R.string.pysanka__look__themes),
            summary = stringRes(R.string.pysanka__look__themes_summary),
            onClick = { navController.navigate(Routes.Settings.Theme) },
        )
        SwitchPreference(
            prefs.keyboard.numberRow,
            title = stringRes(R.string.pysanka__look__number_row),
        )
        SwitchPreference(
            prefs.keyboard.longPressKeyHintEnabled,
            title = stringRes(R.string.pysanka__look__key_hints),
            summary = stringRes(R.string.pysanka__look__key_hints_summary),
        )
        DialogSliderPreference(
            prefs.keyboard.fontSizeMultiplierPortrait,
            title = stringRes(R.string.pysanka__look__font_size),
            valueLabel = { stringRes(R.string.unit__percent__symbol, "v" to it) },
            min = 50,
            max = 150,
            stepIncrement = 5,
        )
        PreferenceGroup(title = stringRes(R.string.pysanka__settings__more)) {
            Preference(
                title = stringRes(R.string.pysanka__look__more_keyboard),
                summary = stringRes(R.string.pysanka__look__more_keyboard_summary),
                onClick = { navController.navigate(Routes.Settings.Keyboard) },
            )
        }
    }
}

@Composable
fun PysankaSoundScreen() = FlorisScreen {
    title = stringRes(R.string.pysanka__settings__sound)
    previewFieldVisible = true

    content {
        SwitchPreference(
            prefs.inputFeedback.hapticEnabled,
            title = stringRes(R.string.pysanka__sound__haptic),
        )
        SwitchPreference(
            prefs.inputFeedback.audioEnabled,
            title = stringRes(R.string.pysanka__sound__audio),
        )
        DialogSliderPreference(
            prefs.inputFeedback.audioVolume,
            title = stringRes(R.string.pysanka__sound__volume),
            valueLabel = { stringRes(R.string.unit__percent__symbol, "v" to it) },
            min = 1,
            max = 100,
            stepIncrement = 1,
            enabledIf = { prefs.inputFeedback.audioEnabled isEqualTo true },
        )
    }
}

@Composable
fun PysankaPrivacyScreen() = FlorisScreen {
    title = stringRes(R.string.pysanka__settings__privacy)
    previewFieldVisible = false
    val navController = LocalNavController.current

    content {
        // What is always true, whatever the settings: shown plainly, so it can be checked
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (fact in listOf(
                R.string.pysanka__privacy__fact_offline,
                R.string.pysanka__privacy__fact_encrypted,
                R.string.pysanka__privacy__fact_backup,
                R.string.pysanka__privacy__fact_incognito,
                R.string.pysanka__privacy__fact_ocr,
            )) {
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringRes(fact), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        SwitchPreference(
            prefs.clipboard.historySkipSensitive,
            title = stringRes(R.string.pysanka__clipboard__skip_sensitive__label),
            summary = stringRes(R.string.pysanka__clipboard__skip_sensitive__summary),
        )
        SwitchPreference(
            prefs.pysankaOcr.blockScreenshots,
            title = stringRes(R.string.pysanka__privacy__block_screenshots),
            summary = stringRes(R.string.pysanka__privacy__block_screenshots_summary),
        )
        ListPreference(
            prefs.suggestion.incognitoMode,
            icon = androidx.compose.ui.graphics.vector.ImageVector.vectorResource(id = R.drawable.ic_incognito),
            title = stringRes(R.string.pysanka__privacy__incognito),
            entries = enumDisplayEntriesOf(IncognitoMode::class),
        )
        Preference(
            title = stringRes(R.string.pysanka__clipboard_copy__title),
            summary = stringRes(R.string.pysanka__clipboard_copy__summary),
            onClick = { navController.navigate(Routes.Settings.ClipboardCopy) },
        )
    }
}

@Composable
fun PysankaMoreScreen() = FlorisScreen {
    title = stringRes(R.string.pysanka__settings__more)
    previewFieldVisible = false
    val navController = LocalNavController.current

    content {
        Preference(
            title = stringRes(R.string.settings__smartbar__title),
            summary = stringRes(R.string.pysanka__more__smartbar_summary),
            onClick = { navController.navigate(Routes.Settings.Smartbar) },
        )
        Preference(
            title = stringRes(R.string.pysanka__typing__more_gestures),
            summary = stringRes(R.string.pysanka__typing__more_gestures_summary),
            onClick = { navController.navigate(Routes.Settings.Gestures) },
        )
        Preference(
            title = stringRes(R.string.pysanka__look__more_keyboard),
            summary = stringRes(R.string.pysanka__look__more_keyboard_summary),
            onClick = { navController.navigate(Routes.Settings.Keyboard) },
        )
        Preference(
            title = stringRes(R.string.pysanka__typing__more_typing),
            summary = stringRes(R.string.pysanka__typing__more_typing_summary),
            onClick = { navController.navigate(Routes.Settings.Typing) },
        )
        Preference(
            title = stringRes(R.string.physical_keyboard__title),
            onClick = { navController.navigate(Routes.Settings.PhysicalKeyboard) },
        )
        Preference(
            title = stringRes(R.string.ext__home__title),
            summary = stringRes(R.string.pysanka__more__extensions_summary),
            onClick = { navController.navigate(Routes.Ext.Home) },
        )
        Preference(
            title = stringRes(R.string.settings__other__title),
            onClick = { navController.navigate(Routes.Settings.Other) },
        )
    }
}
