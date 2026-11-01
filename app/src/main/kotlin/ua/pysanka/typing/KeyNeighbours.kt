package ua.pysanka.typing

/**
 * Pysanka: which letters sit next to each other on the layouts Pysanka sets up (Ukrainian ЙЦУКЕН,
 * English QWERTY), so a slip onto a neighbouring key counts as a likelier typing error.
 */
object KeyNeighbours {
    private val UK = listOf("йцукенгшщзхї", "фівапролджє", "ячсмитьбю")
    private val EN = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    fun forLanguage(language: String): Map<Char, String> = when (language) {
        "uk" -> build(UK)
        "en" -> build(EN)
        else -> emptyMap()
    }

    private fun build(rows: List<String>): Map<Char, String> {
        val out = HashMap<Char, StringBuilder>()
        for ((r, row) in rows.withIndex()) {
            for ((i, c) in row.withIndex()) {
                val sb = out.getOrPut(c) { StringBuilder() }
                // Left and right, and the keys above and below (rows are shifted by about half a key)
                row.getOrNull(i - 1)?.let(sb::append)
                row.getOrNull(i + 1)?.let(sb::append)
                for (other in listOfNotNull(rows.getOrNull(r - 1), rows.getOrNull(r + 1))) {
                    other.getOrNull(i)?.let(sb::append)
                    other.getOrNull(i - 1)?.let(sb::append)
                    other.getOrNull(i + 1)?.let(sb::append)
                }
            }
        }
        // ґ is on the key of г
        out["г".first()]?.append('ґ')
        return out.mapValues { it.value.toString() }
    }
}
