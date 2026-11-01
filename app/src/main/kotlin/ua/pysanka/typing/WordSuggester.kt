package ua.pysanka.typing

/**
 * Pysanka: word suggestions from a frequency word list (assets/ime/dict/<language>.json) and the
 * person's own words, on the phone.
 *
 * - Completions: the most frequent words that start with what has been typed.
 * - Corrections: words one typing error away (a letter missing, extra, wrong or two letters
 *   swapped), a wrong letter next to the intended one on the keyboard counting as more likely.
 * - Auto-correction is careful: only for a word of at least [AUTO_MIN_LENGTH] letters that is not
 *   in the list itself, when one correction is clearly the most likely; never for a word with
 *   digits, nor for one taken back with Backspace ([rejected]); a capitalised word (often a name)
 *   only into a common word.
 * - Apostrophes: ', ’ and ʼ count as the same; the one typed is kept.
 * - The person's own words ([PersonalWords], when given): a list word used often is offered
 *   higher; a word of their own is offered once known and never corrected. Auto-correction goes
 *   only into a list word (a learned word may itself be a typo typed again).
 * - The word before ([PersonalWords] pairs): a word that often followed it is offered higher, and
 *   before anything is typed the usual next words are offered ([predict]).
 *
 * The case of what was typed is kept («Прив» → «Привіт», «ПРИВ» → «ПРИВІТ»).
 */
class WordSuggester(words: Map<String, Int>, private val neighbours: Map<Char, String> = emptyMap()) {
    data class Suggestion(val word: String, val autoCorrect: Boolean)

    companion object {
        const val AUTO_MIN_LENGTH = 3

        /** A correction is applied by itself only if it is this much likelier than the next one. */
        private const val AUTO_MARGIN = 2.0

        /** A word is auto-corrected only into a word at least this common (rare words are often typos themselves). */
        private const val AUTO_MIN_FREQ = 70

        /** A capitalised word (a name, often) is auto-corrected only into a word at least this common. */
        private const val AUTO_CAPITAL_MIN_FREQ = 150

        /** Longer words are not corrected (the longest in the lists has 26 letters). */
        private const val MAX_CORRECTED_LENGTH = 30

        private val APOSTROPHES = charArrayOf('’', 'ʼ')

        /** A list word gains this much per use (up to [BOOST_USES] uses). */
        private const val BOOST = 8
        private const val BOOST_USES = 10

        /** A known word of the person's own: as common as a fairly common list word, more with use. */
        private const val OWN_BASE = 60
        private const val OWN_STEP = 15
        private const val OWN_MAX = 200

        /** A word that followed the previous one gains this share per time (up to [PAIR_USES] times). */
        private const val PAIR_GAIN = 0.6
        private const val PAIR_USES = 5
    }

    /** Frequency (1..255) by the word in small letters. */
    private val freq: Map<String, Int> = HashMap<String, Int>().also { m ->
        for ((w, f) in words) m.merge(w.lowercase(), f, ::maxOf)
    }

    /** Names keep their capital letter («київ» → «Київ»). */
    private val display: Map<String, String> = words.keys.filter { it != it.lowercase() }.associateBy { it.lowercase() }

    /** Words sorted alphabetically (for the beginning). */
    private val sorted: Array<String> = freq.keys.sorted().toTypedArray()
    private val alphabet: String = freq.keys.flatMap { it.asIterable() }.toSortedSet().joinToString("")

    /** The most frequent words for each beginning of one or two letters (worked out once). */
    private val shortPrefixes = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    private fun norm(word: String) = PersonalWords.key(word)

    /** How common a word is for this person (0: not a word). */
    private fun score(key: String, own: PersonalWords?): Int {
        val base = freq[key]
        val uses = own?.count(key) ?: 0
        return when {
            base != null -> if (uses > 0) minOf(255, base + BOOST * minOf(uses, BOOST_USES)) else base
            uses >= PersonalWords.KNOWN -> minOf(OWN_MAX, OWN_BASE + OWN_STEP * uses)
            else -> 0
        }
    }

    fun contains(word: String, own: PersonalWords? = null) = score(norm(word), own) > 0

    /** A word the person taught that the list does not have (it can be forgotten). */
    fun isOwn(word: String, own: PersonalWords?): Boolean = norm(word).let { it !in freq && score(it, own) > 0 }

    /** The list's words and the person's known own words (for swipe). */
    fun words(own: PersonalWords? = null): List<String> =
        sorted.map { display[it] ?: it } + own?.known().orEmpty().filter { it !in freq }.map { own!!.display(it) ?: it }

    /** How common a word is (1..255; 0: not a word), the person's use included. */
    fun frequency(word: String, own: PersonalWords? = null): Int = score(norm(word), own)

    /**
     * [rejected]: words whose auto-correction was taken back; they are left as typed.
     * [own]: the person's own words, if any; [previous]: the word before, in the same sentence.
     */
    fun suggest(
        typed: String,
        max: Int,
        rejected: Set<String> = emptySet(),
        own: PersonalWords? = null,
        previous: String? = null,
    ): List<Suggestion> {
        if (typed.isEmpty() || max <= 0) return emptyList()
        val lower = norm(typed)
        val f = { key: String -> score(key, own) }
        val known = f(lower) > 0
        val scored = HashMap<String, Double>()
        val hasDigit = lower.any { it.isDigit() }

        // Corrections first, so a completion of the same word keeps the better score
        if (lower.length in 3..MAX_CORRECTED_LENGTH && !hasDigit) {
            for ((cand, likely) in corrections(lower)) {
                val c = f(cand).takeIf { it > 0 } ?: continue
                scored.merge(cand, c * likely, ::maxOf)
            }
        }
        // Completions: shorter words that are already complete rank a little higher
        val ownStarts = own?.withPrefix(lower, 50).orEmpty()
        for (cand in withPrefix(lower) + ownStarts) {
            val c = f(cand).takeIf { it > 0 }?.toDouble() ?: continue
            val extra = cand.length - lower.length
            scored.merge(cand, c * (if (extra == 0) 1.2 else 1.0 / (1 + extra * 0.05)), ::maxOf)
        }
        if (known) scored[lower] = maxOf(scored[lower] ?: 0.0, f(lower) * 1.5)
        val ranked = scored.entries.sortedByDescending { it.value }.take(max)
        val best = ranked.firstOrNull()
        val second = ranked.getOrNull(1)?.value ?: 0.0
        // Decided before the word before is weighed: a correction must stand on its own
        var auto = best != null && !known && !hasDigit && lower.length >= AUTO_MIN_LENGTH &&
            lower !in rejected && best.key in freq && f(best.key) >= AUTO_MIN_FREQ &&
            (!typed.first().isUpperCase() || f(best.key) >= AUTO_CAPITAL_MIN_FREQ) &&
            best.key.length in (lower.length - 1)..(lower.length + 1) &&
            isOneEditAway(lower, best.key) && best.value >= second * AUTO_MARGIN
        val autoWord = best?.key
        // What usually comes after the previous word (a pair used [PersonalWords.PAIR_KNOWN] times) ranks higher
        val order = if (own != null && previous != null) {
            val prev = norm(previous)
            scored.mapValues { (key, v) ->
                val n = own.pairCount(prev, key)
                if (n >= PersonalWords.PAIR_KNOWN) v * (1 + PAIR_GAIN * minOf(n, PAIR_USES)) else v
            }.entries.sortedByDescending { it.value }.take(max).map { it.key }
        } else {
            ranked.map { it.key }
        }
        // A correction is applied only while it is still the first word offered
        if (order.firstOrNull() != autoWord) auto = false
        val apostrophe = typed.firstOrNull { it == '\'' || it in APOSTROPHES }
        return order.mapIndexed { i, key ->
            val shown = display[key] ?: (if (key !in freq) own?.display(key) else null) ?: key
            val word = if (apostrophe != null) shown.replace('\'', apostrophe) else shown
            Suggestion(matchCase(typed, word), autoCorrect = auto && i == 0)
        }
    }

    /**
     * The words that usually follow [previous] in a sentence (from the person's own typing),
     * before anything is typed.
     */
    fun predict(previous: String, max: Int, own: PersonalWords?): List<String> {
        if (own == null || max <= 0) return emptyList()
        return own.next(norm(previous), max).filter { score(it, own) > 0 }.map { key ->
            display[key] ?: (if (key !in freq) own.display(key) else null) ?: key
        }
    }

    /** Words starting with [prefix], most frequent first (at most 50). */
    private fun withPrefix(prefix: String): List<String> {
        if (prefix.length <= 2) return shortPrefixes.getOrPut(prefix) { scanPrefix(prefix) }
        return scanPrefix(prefix)
    }

    private fun scanPrefix(prefix: String): List<String> {
        var lo = sorted.binarySearch(prefix).let { if (it < 0) -it - 1 else it }
        val out = ArrayList<String>()
        while (lo < sorted.size && sorted[lo].startsWith(prefix)) out.add(sorted[lo++])
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
