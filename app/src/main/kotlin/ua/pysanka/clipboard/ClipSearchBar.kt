package ua.pysanka.clipboard

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import org.florisboard.lib.compose.florisHorizontalScroll
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.snygg.ui.SnyggChip
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggIconButton
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText

/** Pysanka: the clipboard search while it is open, else [smartbar]. */
@Composable
fun ClipSearchOrSmartbar(smartbar: @Composable () -> Unit) {
    val query by ClipSearch.query.collectAsState()
    query?.let { ClipSearchBar(it) } ?: smartbar()
}

/**
 * Pysanka: the clipboard search, in place of the suggestions bar while the keys type into it
 * ([ClipSearch]): back to the clipboard, the query, then the clips found; a tap pastes one.
 */
@Composable
private fun ClipSearchBar(query: String) {
    val context = LocalContext.current
    val clipboardManager by context.clipboardManager()
    val keyboardManager by context.keyboardManager()
    val history by clipboardManager.historyFlow.collectAsState()
    val tags by clipboardManager.tagsFlow.collectAsState()
    val locked = remember { context.getSystemService(android.app.KeyguardManager::class.java) }
        .let { it.isDeviceLocked || it.isKeyguardLocked }
    // Prepared once (in the background) when the history or the tags change, not for every key
    val index by produceState(emptyList<ClipSearch.Entry>(), history, tags) {
        value = withContext(Dispatchers.Default) { ClipSearch.index(history.pinned + history.unpinned, tags) }
    }
    val found = remember(index, query, locked) { if (locked) emptyList() else ClipSearch.results(index, query) }
    // Leaving the text keys (another panel, or the keyboard closed) ends the search
    DisposableEffect(Unit) { onDispose { ClipSearch.stop() } }

    SnyggRow(
        FlorisImeUi.ClipboardHeader.elementName,
        modifier = Modifier.fillMaxWidth().height(FlorisImeSizing.smartbarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SnyggIconButton(
            elementName = FlorisImeUi.ClipboardHeaderButton.elementName,
            onClick = {
                ClipSearch.stop()
                keyboardManager.activeState.imeUiMode = ImeUiMode.CLIPBOARD
            },
            modifier = Modifier.sizeIn(maxHeight = FlorisImeSizing.smartbarHeight).aspectRatio(1f),
        ) {
            SnyggIcon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringRes(R.string.pysanka__clip_search__back))
        }
        SnyggIcon(imageVector = Icons.Default.Search)
        SnyggText(
            elementName = FlorisImeUi.ClipboardHeaderText.elementName,
            modifier = Modifier.widthIn(max = 140.dp).padding(horizontal = 6.dp),
            // The cursor: the keys type here
            // The end of a long query, where the typing is
            text = when {
                query.isEmpty() -> stringRes(R.string.pysanka__clip_search__hint)
                query.length > 18 -> "…" + query.takeLast(17).let { if (it.first().isLowSurrogate()) it.drop(1) else it } + "▏"
                else -> "$query▏"
            },
        )
        SnyggRow(
            elementName = FlorisImeUi.ClipboardFilterRow.elementName,
            modifier = Modifier.weight(1f),
            clickAndSemanticsModifier = Modifier.florisHorizontalScroll(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                locked || query.isBlank() -> {}
                found.isEmpty() -> SnyggText(text = stringRes(R.string.pysanka__clip_search__nothing))
                else -> for (item in found) {
                    SnyggChip(
                        elementName = FlorisImeUi.ClipboardFilterChip.elementName,
                        onClick = {
                            clipboardManager.pasteItem(item)
                            ClipSearch.stop()
                        },
                        text = when {
                            item.type != ItemType.TEXT -> stringRes(R.string.pysanka__clip_search__image) +
                                item.text?.takeIf { it.isNotBlank() }?.let { ": " + label(it) }.orEmpty()
                            item.isSensitive -> stringRes(R.string.clipboard__sensitive_clip_content)
                            else -> label(item.text.orEmpty())
                        },
                    )
                }
            }
        }
    }
}

/** One short line of a clip. */
private fun label(text: String): String {
    val line = text.trim().replace(Regex("\\s+"), " ")
    if (line.length <= 40) return line
    val end = if (Character.isHighSurrogate(line[39])) 39 else 40
    return line.substring(0, end) + "…"
}
