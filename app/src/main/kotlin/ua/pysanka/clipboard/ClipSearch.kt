package ua.pysanka.clipboard

import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Pysanka: searching the clipboard history from the keyboard. While searching, the keys type into
 * the search (shown in place of the suggestions bar), not into the field, and nothing is learned
 * from them. The query is kept in memory only and is gone when the search ends.
 *
 * A clip matches when every word of the query is found in its text or in the names of its tags
 * (small or capital letters, and ' ’ ʼ, the same). Clips marked sensitive (passwords) are found
 * only by their tags.
 */
object ClipSearch {
    const val MAX_CHARS = 100
    const val MAX_RESULTS = 30

    private val state = MutableStateFlow<String?>(null)

    /** The query while searching; null when not searching. */
    val query: StateFlow<String?> = state

    val active: Boolean get() = state.value != null

    fun start() {
        state.value = ""
    }

    fun stop() {
        state.value = null
    }

    fun type(text: String) = state.update { q -> q?.let { (it + text).take(MAX_CHARS) } }

    /** A swiped word: after a space, unless the query is empty or already ends with one. */
    fun typeWord(word: String) = state.update { q ->
        q?.let { (if (it.isEmpty() || it.endsWith(' ')) it + word else "$it $word").take(MAX_CHARS) }
    }

    /** The last character (an emoji or another character in two halves as one). */
    fun deleteChar() = state.update { q ->
        if (q.isNullOrEmpty()) q else q.substring(0, q.offsetByCodePoints(q.length, -1))
    }

    fun deleteWord() = state.update { q -> q?.trimEnd()?.let { t -> t.substring(0, t.lastIndexOf(' ') + 1) } }

    fun normalize(s: String): String = s.lowercase().replace('’', '\'').replace('ʼ', '\'')

    fun words(query: String): List<String> = normalize(query).split(' ', '\n', '\t').filter { it.isNotEmpty() }

    /** Whether a clip with [text] (null: none, or not to be searched) and [tagNames] has every word. */
    fun matches(text: String?, tagNames: List<String>, words: List<String>): Boolean {
        if (words.isEmpty()) return false
        val t = text?.let(::normalize)
        val tags = tagNames.map(::normalize)
        return words.all { w -> t?.contains(w) == true || tags.any { it.contains(w) } }
    }

    /** The clips that match, in the order given (pinned first, then the newest), at most [MAX_RESULTS]. */
    fun results(items: List<ClipboardItem>, tags: List<TagView>, query: String): List<ClipboardItem> {
        val words = words(query)
        if (words.isEmpty()) return emptyList()
        val names = tags.associate { it.id to it.name }
        return items.asSequence().filter { item ->
            val text = if (item.type == ItemType.TEXT && !item.isSensitive) item.text else null
            matches(text, item.tagIds().mapNotNull { names[it] }, words)
        }.take(MAX_RESULTS).toList()
    }
}
