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
 * - A word the list does not know becomes "known" ([KNOWN] uses) after it is typed twice, or at
 *   once when the person insists on it (taps it as typed, or takes its auto-correction back).
 *   A known word is offered and never auto-corrected.
 * - At most [max] words; the least used and longest unused go first.
 *
 * Kept by the word in small letters (apostrophes ' ’ ʼ the same); [display] is the form with a
 * capital letter when the word was typed so inside a sentence (a name).
 */
class PersonalWords(private val max: Int = MAX_WORDS) {
    companion object {
        /** Uses after which a word that is not in the word list is offered. */
        const val KNOWN = 2

        const val MAX_WORDS = 3000

        /** Counts stop growing here (enough to tell often from rarely). */
        private const val MAX_COUNT = 1000

        private const val MIN_LENGTH = 2
        private const val MAX_LENGTH = 30
        private const val FORMAT = 1

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
                    c.isLetter() -> {
                        val s = Character.UnicodeScript.of(c.code)
                        if (script == null) script = s else if (s != script) return false
                    }
                    c == '\'' || c == '-' || c in APOSTROPHES -> {}
                    else -> return false
                }
            }
            return true
        }

        /** Reads what [encode] wrote; throws [IOException] on anything else. */
        fun decode(bytes: ByteArray, max: Int = MAX_WORDS): PersonalWords {
            val out = PersonalWords(max)
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readInt() != FORMAT) throw IOException("Unknown format")
                val n = input.readInt()
                if (n < 0 || n > max * 2) throw IOException("Bad size")
                repeat(n) {
                    val key = input.readUTF()
                    val display = input.readUTF().takeIf { it.isNotEmpty() }
                    val count = input.readInt()
                    val day = input.readInt()
                    if (key != key(key) || !isLearnable(key) || count !in 1..MAX_COUNT) throw IOException("Bad entry")
                    if (display != null && key(display) != key) throw IOException("Bad entry")
                    out.words[key] = Entry(count, display, day)
                }
                if (input.read() != -1) throw IOException("Trailing data")
            }
            out.trim()
            return out
        }
    }

    private class Entry(var count: Int, var display: String?, var day: Int)

    /** Sorted, so the words with a beginning are found at once. */
    private val words = TreeMap<String, Entry>()

    val size: Int @Synchronized get() = words.size

    /**
     * One use of [word]. [insisted]: the person chose it as typed (it becomes known at once).
     * [midSentence]: not the first word of a sentence, so a capital letter means a name.
     * Returns whether anything changed.
     */
    @Synchronized
    fun learn(word: String, insisted: Boolean, midSentence: Boolean, day: Int): Boolean {
        if (!isLearnable(word)) return false
        val key = key(word)
        val capital = midSentence && word != key && word.first().isUpperCase()
        val e = words[key]
        if (e == null) {
            words[key] = Entry(if (insisted) KNOWN else 1, if (capital) normalized(word) else null, day)
            if (words.size > max) trim()
        } else {
            e.count = minOf(MAX_COUNT, maxOf(e.count + 1, if (insisted) KNOWN else 0))
            e.day = day
            // Typed in small letters inside a sentence once: not a name after all
            if (capital) e.display = normalized(word) else if (midSentence && word == key) e.display = null
        }
        return true
    }

    /** Takes one use back (the word was an auto-correction the person undid). */
    @Synchronized
    fun unlearn(word: String): Boolean {
        val key = key(word)
        val e = words[key] ?: return false
        if (--e.count <= 0) words.remove(key)
        return true
    }

    @Synchronized
    fun forget(word: String): Boolean = words.remove(key(word)) != null

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
        }
        return bytes.toByteArray()
    }

    private fun normalized(word: String) = APOSTROPHES.fold(word) { s, c -> s.replace(c, '\'') }

    /** Down to [max] words: the least used go first, of those the longest unused. */
    private fun trim() {
        if (words.size <= max) return
        val drop = words.entries.sortedWith(compareBy({ it.value.count }, { it.value.day })).take(words.size - max).map { it.key }
        drop.forEach { words.remove(it) }
    }
}
