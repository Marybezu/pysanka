package ua.pysanka.ai

/**
 * Pysanka: [AiAction.HASHTAGS]. The model only names the hashtags; the text itself is never
 * rewritten: the hashtags go under it, on their own line. Only well-formed hashtags (#, then letters,
 * digits or _), each once (in any letters), at most [MAX]; ones the text already has are left out.
 */
internal object Hashtags {
    const val MAX = 8

    private val TAG = Regex("#[\\p{L}\\p{N}_]*\\p{L}[\\p{L}\\p{N}_]*")

    fun of(answer: String, text: String = ""): List<String> {
        val have = TAG.findAll(text).map { it.value.lowercase() }.toSet()
        return TAG.findAll(answer).map { it.value }
            .filter { it.length in 3..40 && it.lowercase() !in have }
            .distinctBy { it.lowercase() }.take(MAX).toList()
    }

    /** The text with the hashtags under it; empty when the answer had none (so it counts as failed). */
    fun append(text: String, answer: String): String {
        val tags = of(answer, text)
        return if (tags.isEmpty()) "" else text.trimEnd() + "\n\n" + tags.joinToString(" ")
    }
}
