package ua.pysanka.ai

/**
 * Pysanka: whether a change of style changed enough: most words the same and in the same order
 * means "almost nothing"; for [AiAction.SHORTER], less than a tenth shorter also does.
 */
object TextChanges {
    /** Above this share of words kept in order, the text counts as unchanged. */
    private const val SAME = 0.85

    /** Longer texts are compared by their first words only (enough to tell, and quick). */
    private const val MAX_WORDS = 400

    fun tooLittle(action: AiAction, before: String, after: String): Boolean {
        if (action == AiAction.SHORTER && after.trim().length > before.trim().length * 0.9) return true
        return sameShare(before, after) > SAME
    }

    /** The share of words kept, in order (longest common run of words over the longer text). */
    fun sameShare(a: String, b: String): Double {
        val x = words(a)
        val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return if (x.isEmpty() && y.isEmpty()) 1.0 else 0.0
        var prev = IntArray(y.size + 1)
        var cur = IntArray(y.size + 1)
        for (i in 1..x.size) {
            for (j in 1..y.size) {
                cur[j] = if (x[i - 1] == y[j - 1]) prev[j - 1] + 1 else maxOf(prev[j], cur[j - 1])
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[y.size].toDouble() / maxOf(x.size, y.size)
    }

    private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}'’ʼ]+")).filter { it.isNotEmpty() }.take(MAX_WORDS)
}
