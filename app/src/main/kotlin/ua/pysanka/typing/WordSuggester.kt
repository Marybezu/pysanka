package ua.pysanka.typing

/**
 * Pysanka: word suggestions from a frequency word list (assets/ime/dict/<language>.json), on the
 * phone and without learning anything.
 *
 * - Completions: the most frequent words that start with what has been typed.
 * - Corrections: words one typing error away (a letter missing, extra, wrong or two letters
 *   swapped), a wrong letter next to the intended one on the keyboard counting as more likely.
 * - Auto-correction is careful: only for a word of at least [AUTO_MIN_LENGTH] letters that is not
 *   in the list itself, when one correction is clearly the most likely.
 *
 * The case of what was typed is kept («Прив» → «Привіт», «ПРИВ» → «ПРИВІТ»).
 */
class WordSuggester(words: Map<String, Int>, private val neighbours: Map<Char, String> = emptyMap()) {
    data class Suggestion(val word: String, val autoCorrect: Boolean)

    companion object {
        const val AUTO_MIN_LENGTH = 3

        /** A correction is applied by itself only if it is this much likelier than the next one. */
        private const val AUTO_MARGIN = 2.0

        /** At most this many words with the same beginning are looked at (they are by frequency). */
        private const val PREFIX_SCAN = 4000
    }

    /** Words sorted alphabetically (for the beginning), with their frequency (1..255). */
    private val sorted: Array<String> = words.keys.sorted().toTypedArray()
    private val freq: Map<String, Int> = words
    private val alphabet: String = words.keys.flatMap { it.asIterable() }.toSortedSet().joinToString("")

    fun contains(word: String) = word.lowercase() in freq

    fun words(): List<String> = sorted.asList()

    fun frequency(word: String): Int = freq[word.lowercase()] ?: 0

    fun suggest(typed: String, max: Int): List<Suggestion> {
        if (typed.isEmpty() || max <= 0) return emptyList()
        val lower = typed.lowercase()
        val known = lower in freq
        val scored = HashMap<String, Double>()

        // Corrections first, so a completion of the same word keeps the better score
        if (lower.length >= 2) {
            for ((cand, likely) in corrections(lower)) {
                val f = freq[cand] ?: continue
                scored.merge(cand, f * likely, ::maxOf)
            }
        }
        // Completions: shorter words that are already complete rank a little higher
        for (cand in withPrefix(lower)) {
            val f = freq.getValue(cand).toDouble()
            val extra = cand.length - lower.length
            scored.merge(cand, f * (if (extra == 0) 1.2 else 1.0 / (1 + extra * 0.05)), ::maxOf)
        }
        if (known) scored[lower] = maxOf(scored[lower] ?: 0.0, freq.getValue(lower) * 1.5)

        val ranked = scored.entries.sortedByDescending { it.value }.take(max)
        val best = ranked.firstOrNull()
        val second = ranked.getOrNull(1)?.value ?: 0.0
        val auto = best != null && !known && lower.length >= AUTO_MIN_LENGTH &&
            best.key.length in (lower.length - 1)..(lower.length + 1) &&
            isOneEditAway(lower, best.key) && best.value >= second * AUTO_MARGIN
        return ranked.mapIndexed { i, e -> Suggestion(matchCase(typed, e.key), autoCorrect = auto && i == 0) }
    }

    /** Words starting with [prefix], most frequent first. */
    private fun withPrefix(prefix: String): List<String> {
        var lo = sorted.binarySearch(prefix).let { if (it < 0) -it - 1 else it }
        val out = ArrayList<String>()
        while (lo < sorted.size && sorted[lo].startsWith(prefix) && out.size < PREFIX_SCAN) out.add(sorted[lo++])
        return out.sortedByDescending { freq.getValue(it) }.take(50)
    }

    /** Words one typing error away, with how likely that error is (1 = as likely as a right word). */
    private fun corrections(w: String): Sequence<Pair<String, Double>> = sequence {
        for (i in w.indices) {
            // A letter too many
            yield(w.removeRange(i, i + 1) to 0.3)
            // Two letters swapped
            if (i + 1 < w.length) yield((w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2)) to 0.4)
            // A wrong letter: a neighbouring key is the likelier slip
            for (c in alphabet) {
                if (c == w[i]) continue
                val near = neighbours[w[i]]?.contains(c) == true
                yield((w.substring(0, i) + c + w.substring(i + 1)) to if (near) 0.45 else 0.12)
            }
        }
        // A letter missing
        for (i in 0..w.length) for (c in alphabet) yield((w.substring(0, i) + c + w.substring(i)) to 0.25)
    }

    private fun isOneEditAway(a: String, b: String): Boolean {
        if (a == b) return false
        if (a.length == b.length) {
            val diff = a.indices.filter { a[it] != b[it] }
            return diff.size == 1 || (diff.size == 2 && diff[1] == diff[0] + 1 && a[diff[0]] == b[diff[1]] && a[diff[1]] == b[diff[0]])
        }
        val (s, l) = if (a.length < b.length) a to b else b to a
        if (l.length - s.length != 1) return false
        val i = s.indices.firstOrNull { s[it] != l[it] } ?: s.length
        return s.substring(i) == l.substring(i + 1)
    }

    private fun matchCase(typed: String, word: String): String = when {
        typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
        typed.first().isUpperCase() -> word.replaceFirstChar { it.uppercaseChar() }
        else -> word
    }
}
