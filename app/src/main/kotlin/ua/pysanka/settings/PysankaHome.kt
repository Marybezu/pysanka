package ua.pysanka.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SentimentSatisfiedAlt
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.jetpref.datastore.model.collectAsState
import org.florisboard.lib.compose.stringRes

/**
 * Pysanka: the settings home. Six tiles that show their current state, then Privacy, the on-device
 * AI (later), "More" and About. Rare settings live behind "More" in each section.
 */
@Composable
fun PysankaHome() {
    val prefs by FlorisPreferenceStore
    val navController = LocalNavController.current
    val context = LocalContext.current
    val clipboardManager by context.clipboardManager()

    val glide by prefs.glide.enabled.collectAsState()
    val suggestions by prefs.suggestion.enabled.collectAsState()
    val numberRow by prefs.keyboard.numberRow.collectAsState()
    val haptic by prefs.inputFeedback.hapticEnabled.collectAsState()
    val audio by prefs.inputFeedback.audioEnabled.collectAsState()
    val history by prefs.clipboard.historyEnabled.collectAsState()
    val skipPasswords by prefs.clipboard.historySkipSensitive.collectAsState()
    val emojiHistory by prefs.emoji.historyEnabled.collectAsState()
    val tags by clipboardManager.tagsFlow.collectAsState()

    val on = stringRes(R.string.pysanka__settings__on)
    val off = stringRes(R.string.pysanka__settings__off)
    fun state(v: Boolean) = if (v) on else off

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringRes(R.string.pysanka__settings__tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        TileRow(
            Tile(Icons.Default.Language, stringRes(R.string.pysanka__settings__languages), stringRes(R.string.pysanka__settings__languages_summary)) {
                navController.navigate(Routes.Settings.Localization)
            },
            Tile(
                Icons.Outlined.Keyboard, stringRes(R.string.pysanka__settings__typing),
                stringRes(R.string.pysanka__settings__typing_summary, "glide" to state(glide), "suggestions" to state(suggestions)),
            ) { navController.navigate(Routes.Settings.PysankaTyping) },
        )
        TileRow(
            Tile(
                Icons.Outlined.Palette, stringRes(R.string.pysanka__settings__look),
                stringRes(R.string.pysanka__settings__look_summary, "numbers" to state(numberRow)),
            ) { navController.navigate(Routes.Settings.PysankaLook) },
            Tile(
                Icons.Default.Vibration, stringRes(R.string.pysanka__settings__sound),
                stringRes(R.string.pysanka__settings__sound_summary, "haptic" to state(haptic), "audio" to state(audio)),
            ) { navController.navigate(Routes.Settings.PysankaSound) },
        )
        TileRow(
            Tile(
                Icons.AutoMirrored.Outlined.Assignment, stringRes(R.string.pysanka__settings__clipboard),
                if (history) {
                    stringRes(R.string.pysanka__settings__clipboard_summary, "tags" to tags.size.toString())
                } else {
                    stringRes(R.string.pysanka__settings__clipboard_summary_off)
                },
            ) { navController.navigate(Routes.Settings.Clipboard) },
            Tile(
                Icons.Default.SentimentSatisfiedAlt, stringRes(R.string.pysanka__settings__emoji),
                stringRes(R.string.pysanka__settings__emoji_summary, "history" to state(emojiHistory)),
            ) { navController.navigate(Routes.Settings.Media) },
        )
        WideTile(
            Icons.Default.Lock, stringRes(R.string.pysanka__settings__privacy),
            stringRes(if (skipPasswords) R.string.pysanka__settings__privacy_summary else R.string.pysanka__settings__privacy_summary_keeps),
            highlighted = true,
        ) { navController.navigate(Routes.Settings.PysankaPrivacy) }
        WideTile(
            Icons.Default.AutoAwesome, stringRes(R.string.pysanka__settings__ai),
            stringRes(R.string.pysanka__settings__ai_soon), enabled = false,
        ) {}
        LinkRow(Icons.Outlined.Tune, stringRes(R.string.pysanka__settings__more), stringRes(R.string.pysanka__settings__more_summary)) {
            navController.navigate(Routes.Settings.PysankaMore)
        }
        LinkRow(Icons.Outlined.Info, stringRes(R.string.about__title), null) {
            navController.navigate(Routes.Settings.About)
        }
    }
}

private class Tile(val icon: ImageVector, val title: String, val summary: String, val onClick: () -> Unit)

@Composable
private fun TileRow(a: Tile, b: Tile) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        for (t in listOf(a, b)) {
            Card(
                onClick = t.onClick,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.weight(1f).heightIn(min = 108.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(t.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(t.title, style = MaterialTheme.typography.titleMedium)
                    Text(t.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun WideTile(
    icon: ImageVector, title: String, summary: String,
    highlighted: Boolean = false, enabled: Boolean = true, onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        border = if (highlighted) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LinkRow(icon: ImageVector, title: String, summary: String?, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (summary != null) {
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}
