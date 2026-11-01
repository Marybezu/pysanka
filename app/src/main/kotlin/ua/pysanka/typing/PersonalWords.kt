package ua.pysanka.typing

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.TreeMap

/**
 * Pysanka: the words one person uses, for one language, in memory. [UserWords] keeps them
 * encrypted on the phone; this class only counts.
 *
 * - Every finished word counts once. A word the word list knows is offered a little higher the
 *   more it is used.
 * - A word the list does not know becomes "known" ([KNOWN] uses) after it is typed three times, or at
 *   once when the person insists on it (taps it as typed, or takes its auto-correction back).
 *   A known word is offered and never auto-corrected.
 * - At most [max] words; the least used and longest unused go first.
 * - Pairs of words typed one after the other (in the same sentence), for the next word: offered
 *   after [PAIR_KNOWN] uses; at most [maxPairs].
 *
 * Kept by the word in small letters (apostrophes ' ’ ʼ the same); [display] is the form with a
 * capital letter when the word was typed so inside a sentence (a name).
 */
class PersonalWords(private val max: Int = MAX_WORDS, private val maxPairs: Int = MAX_PAIRS) {
    companion object {
        /** Uses after which a word that is not in the word list is offered. */
        const val KNOWN = 3

        const val MAX_WORDS = 3000

        /** Uses after which the second word of a pair is offered after the first. */
        const val PAIR_KNOWN = 3

        const val MAX_PAIRS = 6000

        /** Counts stop growing here (enough to tell often from rarely). */
        private const val MAX_COUNT = 1000

        private const val MIN_LENGTH = 2
        private const val MAX_LENGTH = 30
        /** 1: words; 2: words and pairs. */
        private const val FORMAT = 2

        private val APOSTROPHES = charArrayOf('’', 'ʼ')

        /** The key a word is kept and looked up by. */
        fun key(word: String): String = word.lowercase().let { w -> APOSTROPHES.fold(w) { s, c -> s.replace(c, '\'') } }

        /**
         * Only something that looks like a word: letters of one alphabet, with apostrophes or
         * hyphens inside; no digits, addresses or codes.
         */
        fun isLearnable(word: String): Boolean {
            if (word.length !in MIN_LENGTH..MAX_LENGTH) return false
            if (!word.first().isLetter() || !word.last().isLetter()) return false
            var script: Character.UnicodeScript? = null
            for (c in word) {
                when {
                    // Before letters: ʼ counts as a letter, of no alphabet
                    c == '\'' || c == '-' || c in APOSTROPHES -> {}
                    c.isLetter() -> {
                        val s = Character.UnicodeScript.of(c.code)
                        if (script == null) script = s else if (s != script) return false
                    }
                    else -> return false
                }
            }
            return true
        }

        /** Reads what [encode] wrote; throws [IOException] on anything else (a single odd word is skipped). */
        fun decode(bytes: ByteArray, max: Int = MAX_WORDS, maxPairs: Int = MAX_PAIRS): PersonalWords {
            val out = PersonalWords(max, maxPairs)
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val format = input.readInt()
                if (format != 1 && format != FORMAT) throw IOException("Unknown format")
                val n = input.readInt()
                if (n < 0 || n > max * 2) throw IOException("Bad size")
                repeat(n) {
                    val key = input.readUTF()
                    val display = input.readUTF().takeIf { it.isNotEmpty() }
                    val count = input.readInt()
                    val day = input.readInt()
                    if (key != key(key) || !isLearnable(key) || count !in 1..MAX_COUNT) return@repeat
                    out.words[key] = Entry(count, display?.takeIf { key(it) == key }, day)
                }
                if (format >= 2) {
                    val pairs = input.readInt()
                    if (pairs < 0 || pairs > maxPairs * 2) throw IOException("Bad size")
                    repeat(pairs) {
                        val first = input.readUTF()
                        val second = input.readUTF()
                        val count = input.readInt()
                        val day = input.readInt()
                        if (first != key(first) || second != key(second) || !isLearnable(first) || !isLearnable(second) ||
                            count !in 1..MAX_COUNT
                        ) return@repeat
                        out.pairs[out.pairKey(first, second)] = Entry(count, null, day)
                    }
                }
                if (input.read() != -1) throw IOException("Trailing data")
            }
            out.trim(out.words, max)
            out.trim(out.pairs, maxPairs)
            return out
        }
    }

    private class Entry(var count: Int, var display: String?, var day: Int)

    /** Sorted, so the words with a beginning are found at once. */
    private val words = TreeMap<String, Entry>()

    /** "first second" (keys never have spaces), sorted so a first word's pairs are found at once. */
    private val pairs = TreeMap<String, Entry>()

    private fun pairKey(first: String, second: String) = "$first $second"

    val size: Int @Synchronized get() = words.size

    /**
     * One use of [word]. [insisted]: the person chose it as typed (it becomes known at once).
     * [midSentence]: not the first word of a sentence, so a capital letter means a name.
     * Returns whether anything changed.
     */
    @Synchronized
    fun learn(word: String, insisted: Boolean, midSentence: Boolean, day: Int): Boolean {
        val key = key(word)
        // The key too: small letters can be longer or different («İ»)
        if (!isLearnable(word) || !isLearnable(key)) return false
        // A name (Ярина), not a word in capitals (НАТО, or Caps Lock)
        val capital = midSentence && word.first().isUpperCase() && word.drop(1).any { it.isLowerCase() }
        val e = words[key]
        if (e == null) {
            words[key] = Entry(if (insisted) KNOWN else 1, if (capital) normalized(word) else null, day)
            if (words.size > max) trim(words, max)
        } else {
            e.count = minOf(MAX_COUNT, maxOf(e.count + 1, if (insisted) KNOWN else 0))
            e.day = day
            // Typed in small letters inside a sentence once: not a name after all
            if (capital) e.display = normalized(word) else if (midSentence && word == key) e.display = null
        }
        return true
    }

    /** One use of [word] right after [previous] in a sentence. Returns whether anything changed. */
    @Synchronized
    fun learnPair(previous: String, word: String, day: Int): Boolean {
        val first = key(previous)
        val second = key(word)
        if (!isLearnable(first) || !isLearnable(second)) return false
        val k = pairKey(first, second)
        val e = pairs[k]
        if (e == null) {
            pairs[k] = Entry(1, null, day)
            if (pairs.size > maxPairs) trim(pairs, maxPairs)
        } else {
            e.count = minOf(MAX_COUNT, e.count + 1)
            e.day = day
        }
        return true
    }

    @Synchronized
    fun unlearnPair(previous: String, word: String): Boolean {
        val k = pairKey(key(previous), key(word))
        val e = pairs[k] ?: return false
        if (--e.count <= 0) pairs.remove(k)
        return true
    }

    /** Forgets that [word] follows [previous] (long press on the offered next word). */
    @Synchronized
    fun forgetPair(previous: String, word: String): Boolean = pairs.remove(pairKey(key(previous), key(word))) != null

    /** Words that followed [previousKey] at least [PAIR_KNOWN] times, most used first. */
    @Synchronized
    fun next(previousKey: String, limit: Int): List<String> {
        if (previousKey.isEmpty()) return emptyList()
        return pairs.subMap("$previousKey ", true, "$previousKey!", false).entries
            .filter { it.value.count >= PAIR_KNOWN }
            .sortedWith(compareByDescending<Map.Entry<String, Entry>> { it.value.count }.thenByDescending { it.value.day })
            .take(limit).map { it.key.substringAfter(' ') }
    }

    /** Uses of the pair ([previousKey], [key]). */
    @Synchronized
    fun pairCount(previousKey: String, key: String): Int = pairs[pairKey(previousKey, key)]?.count ?: 0

    val pairCount: Int @Synchronized get() = pairs.size

    /** The keys of the words used at least [KNOWN] times. */
    @Synchronized
    fun known(): List<String> = words.entries.filter { it.value.count >= KNOWN }.map { it.key }

    /** Takes one use back (the word was an auto-correction the person undid). */
    @Synchronized
    fun unlearn(word: String): Boolean {
        val key = key(word)
        val e = words[key] ?: return false
        if (--e.count <= 0) words.remove(key)
        return true
    }

    /** Forgets the word and every pair it is in. */
    @Synchronized
    fun forget(word: String): Boolean {
        val key = key(word)
        val inPairs = pairs.keys.filter { it.substringBefore(' ') == key || it.substringAfter(' ') == key }
        inPairs.forEach { pairs.remove(it) }
        return (words.remove(key) != null) or inPairs.isNotEmpty()
    }

    /** Uses of the word with this [key]; 0 if never. */
    @Synchronized
    fun count(key: String): Int = words[key]?.count ?: 0

    @Synchronized
    fun display(key: String): String? = words[key]?.display

    /** Keys starting with [prefix], most used first. */
    @Synchronized
    fun withPrefix(prefix: String, limit: Int): List<String> {
        if (prefix.isEmpty()) return emptyList()
        return words.subMap(prefix, true, prefix + Char.MAX_VALUE, false).entries
            .sortedByDescending { it.value.count }.take(limit).map { it.key }
    }

    @Synchronized
    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(FORMAT)
            out.writeInt(words.size)
            for ((key, e) in words) {
                out.writeUTF(key)
                out.writeUTF(e.display.orEmpty())
                out.writeInt(e.count)
                out.writeInt(e.day)
            }
            out.writeInt(pairs.size)
            for ((k, e) in pairs) {
                out.writeUTF(k.substringBefore(' '))
                out.writeUTF(k.substringAfter(' '))
                out.writeInt(e.count)
                out.writeInt(e.day)
            }
        }
        return bytes.toByteArray()
    }

    private fun normalized(word: String) = APOSTROPHES.fold(word) { s, c -> s.replace(c, '\'') }

    /** Down to [limit] entries: the least used go first, of those the longest unused. */
    private fun trim(map: TreeMap<String, Entry>, limit: Int) {
        if (map.size <= limit) return
        val drop = map.entries.sortedWith(compareBy({ it.value.count }, { it.value.day })).take(map.size - limit).map { it.key }
        drop.forEach { map.remove(it) }
    }
}
