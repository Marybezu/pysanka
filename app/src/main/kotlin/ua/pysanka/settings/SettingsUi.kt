package ua.pysanka.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Pysanka: the parts the settings are made of, after Sprotyv's settings: each theme is a block
 * with a coloured header (its name and what is set, so the list reads at a glance) that opens and
 * closes; inside, whole-row switches, choices, a slider and links with an arrow. Plain Compose, so
 * the screens can be drawn on the computer for review.
 */

/** A block's colours: the header's fill and the ink on it. */
data class Tint(val container: Color, val ink: Color)

/** One calm colour for every block (the red of the icon, softened): the header is what says "a block". */
@Composable
fun blockTint(): Tint {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (dark) Tint(Color(0xFF3A2320), Color(0xFFF3C7BE)) else Tint(Color(0xFFF8E3DE), Color(0xFF7A231B))
}

/**
 * One theme of the settings. Closed, it shows its [title] and [summary]; a tap opens it.
 * [padded] false for blocks of rows that draw their own dividers.
 */
@Composable
fun Block(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    startOpen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tint = blockTint()
    var open by rememberSaveable(title) { mutableStateOf(startOpen) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, tint.container),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val stateText = org.florisboard.lib.compose.stringRes(
            if (open) dev.patrickgold.florisboard.R.string.pysanka__settings__block_open else dev.patrickgold.florisboard.R.string.pysanka__settings__block_closed,
        )
        // The whole header is one control: read as a heading with its state, opened or closed with a tap
        Row(
            Modifier.fillMaxWidth().background(tint.container)
                .semantics(mergeDescendants = true) {
                    heading()
                    stateDescription = stateText
                }
                .clickable(role = Role.Button) { open = !open }
                .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = tint.ink)
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = BlockTitle, color = tint.ink)
                if (summary != null && !open) Text(summary, style = MaterialTheme.typography.bodyMedium, color = tint.ink)
            }
            Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null, tint = tint.ink)
        }
        if (open) Column(Modifier.padding(vertical = 4.dp), content = content)
    }
}

/** A block's title: larger and bolder than anything inside it. */
val BlockTitle: androidx.compose.ui.text.TextStyle
    @Composable get() = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)

/** A whole-row switch: the name, a short line on what it does, the switch. */
@Composable
fun ToggleRow(title: String, checked: Boolean, hint: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowText(title, hint, enabled, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A row that opens a screen: the name, a short line, an arrow. */
@Composable
fun LinkRow(title: String, hint: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowText(title, hint, true, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A row that does something at once (with its own confirmation where needed). */
@Composable
fun ActionRow(title: String, hint: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { RowText(title, hint, true, Modifier.weight(1f)) }
}

/** One of a few choices: the name and the chosen one; a tap shows them all. */
@Composable
fun <T> ChoiceRow(title: String, options: List<Pair<T, String>>, selected: T, enabled: Boolean = true, onSelect: (T) -> Unit) {
    var asking by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled) { asking = true }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { RowText(title, options.firstOrNull { it.first == selected }?.second, enabled, Modifier.weight(1f)) }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(title) },
            text = {
                Column {
                    for ((value, label) in options) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(selected = value == selected, role = Role.RadioButton) { asking = false; onSelect(value) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = value == selected, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { asking = false }) { Text(org.florisboard.lib.compose.stringRes(dev.patrickgold.florisboard.R.string.action__cancel)) } },
        )
    }
}

/** A value on a scale, shown as it is set. */
@Composable
fun SliderRow(title: String, value: Int, range: IntRange, step: Int, label: @Composable (Int) -> String, enabled: Boolean = true, onChange: (Int) -> Unit) {
    var v by androidx.compose.runtime.remember(value) { mutableStateOf(value.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), color = textColor(enabled))
            Text(label(v.roundToInt()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = v,
            onValueChange = { v = it },
            onValueChangeFinished = { onChange(v.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = ((range.last - range.first) / step - 1).coerceAtLeast(0),
            enabled = enabled,
        )
    }
}

/** A line of explanation inside a block. */
@Composable
fun BlockNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/** A hairline between groups of rows inside one block. */
@Composable
fun BlockDivider() = HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun RowText(title: String, hint: String?, enabled: Boolean, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = textColor(enabled))
        if (!hint.isNullOrBlank()) {
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.6f))
        }
    }
}

@Composable
private fun textColor(enabled: Boolean) = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.5f)
