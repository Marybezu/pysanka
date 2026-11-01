package ua.pysanka.typing

/**
 * Pysanka: the word right before the cursor in the same sentence, for word pairs: a whole word,
 * then only spaces (or none yet, when [of]'s spaceComing: a tapped suggestion's space comes with the
 * next word). After a full stop, a comma, a line break or a number there is none.
 */
object PreviousWord {
    private val WORD = Regex("""(?<![\p{L}\p{N}'’ʼ-])([\p{L}'’ʼ-]+) +$""")
    private val WORD_AT_END = Regex("""(?<![\p{L}\p{N}'’ʼ-])([\p{L}'’ʼ-]+)$""")

    /** Enough for the longest learnable word, a few spaces and the character before it. */
    private const val TAIL = 48

    fun of(textBefore: String, spaceComing: Boolean = false): String? {
        val tail = textBefore.takeLast(TAIL)
        val word = (if (spaceComing) WORD_AT_END else WORD).find(tail)?.groups?.get(1) ?: return null
        // Cut by the tail: may be the end of a longer word
        if (word.range.first == 0 && tail.length < textBefore.length) return null
        return word.value.takeIf { PersonalWords.isLearnable(it) }
    }
}
