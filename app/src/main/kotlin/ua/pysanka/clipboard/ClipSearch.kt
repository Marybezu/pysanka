package ua.pysanka.clipboard

import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Pysanka: searching the clipboard history from the keyboard. While searching, the keys type into
 * the search (shown in place of the suggestions bar), not into the field, and nothing is learned
 * from them. The query is kept in memory only and is gone when the search ends.
 *
 * A clip matches when every word of the query is found in its text (for a picture, the text
 * recognised in it) or in the names of its tags (small or capital letters, and ' ’ ʼ, the same).
 * Clips marked sensitive (passwords) are found only by their tags.
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

    /** The words of a query, split at any space (a no-break one too). */
    fun words(query: String): List<String> {
        val q = normalize(query)
        val out = mutableListOf<String>()
        var start = 0
        for (i in 0..q.length) {
            if (i < q.length && !Character.isWhitespace(q[i]) && !Character.isSpaceChar(q[i])) continue
            if (i > start) out += q.substring(start, i)
            start = i + 1
        }
        return out
    }

    /** A clip with what it is found by, in small letters, prepared once for every key typed. */
    class Entry(val item: ClipboardItem, val text: String, val tags: List<String>)

    /** [items] ready to search (the order kept: pinned first, then the newest). */
    fun index(items: List<ClipboardItem>, tags: List<TagView>): List<Entry> {
        val names = tags.associate { it.id to normalize(it.name) }
        return items.map { item ->
            Entry(item, if (item.isSensitive) "" else normalize(item.text.orEmpty()), item.tagIds().mapNotNull { names[it] })
        }
    }

    /** The clips that have every word of [query], at most [MAX_RESULTS]. */
    fun results(index: List<Entry>, query: String): List<ClipboardItem> {
        val words = words(query)
        if (words.isEmpty()) return emptyList()
        return index.asSequence()
            .filter { e -> words.all { w -> e.text.contains(w) || e.tags.any { it.contains(w) } } }
            .map { it.item }.take(MAX_RESULTS).toList()
    }
}
