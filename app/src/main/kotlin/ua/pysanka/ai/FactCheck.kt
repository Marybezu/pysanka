package ua.pysanka.ai

/**
 * Pysanka: a check after the model, for summaries. A small model may slip in a number, a date or a
 * name that the text does not have. Every number and every name in the summary is looked up in the
 * original; a line with one that is not there is removed (and counted, so the person is told).
 *
 * Numbers are compared by their digits, whole or by their parts (so "1 250" is found in "1250,00",
 * and 2026 in 31.10.2026), never as a piece of a longer number (5000 is not in 125000).
 * Names are words with a capital letter inside a sentence; they are compared by their start, with
 * vowels evened out, so that Ukrainian endings and alternations (Київ / Києві) still match.
 */
object FactCheck {
    data class Result(val text: String, val removed: Int, val allRemoved: Boolean)

    private val number = Regex("""\d(?:[\d  .,:/\-]*\d)?""")
    private val listMarker = Regex("""^\s*(?:[•*\-–—]|\d{1,2}[.)])\s*""")
    private val word = Regex("""[\p{L}][\p{L}'’\-]*""")
    private val sentenceStart = Regex("""(^|[.!?…:]\s+|^\s*[•*\-–—]\s*|^\s*\d{1,2}[.)]\s*)$""")

    fun check(source: String, summary: String): Result {
        // Each number of the text as a whole, and its parts (31.10.2026 → 31, 10, 2026; 1250,00 → 1250)
        val sourceNumbers = HashSet<String>()
        for (m in number.findAll(source)) {
            sourceNumbers.add(digits(m.value))
            m.value.split('.', ',', ':', '/', '-').map { digits(it) }.filterTo(sourceNumbers) { it.isNotEmpty() }
        }
        val sourceStems = word.findAll(source).map { stem(it.value) }.toHashSet()
        val lines = summary.lines()
        var removed = 0
        val kept = lines.filter { line ->
            if (line.isBlank()) return@filter true
            val body = line.replaceFirst(listMarker, "")
            val badNumber = number.findAll(body).any { m ->
                val d = digits(m.value)
                d.isNotEmpty() && d !in sourceNumbers
            }
            val badName = word.findAll(body).any { m ->
                val w = m.value
                w.length >= 3 && w[0].isUpperCase() && !isSentenceStart(body, m.range.first) &&
                    stem(w) !in sourceStems
            }
            val ok = !badNumber && !badName
            if (!ok) removed++
            ok
        }
        val text = kept.joinToString("\n").trim()
        return if (text.isBlank() && removed > 0) {
            // Nothing would be left: keep the model's text, but say it could not be confirmed
            Result(summary, removed, allRemoved = true)
        } else {
            Result(text, removed, allRemoved = false)
        }
    }

    private fun digits(s: String) = s.filter { it.isDigit() }

    private fun isSentenceStart(line: String, at: Int): Boolean = sentenceStart.containsMatchIn(line.substring(0, at))

    /** The start of a word, lower case, with vowels and soft signs evened out (endings change them). */
    private fun stem(w: String): String {
        val l = w.lowercase().replace("’", "'")
        val even = buildString {
            for (c in l) append(if (c in "аеєиіїоуюяыэё'ьaeiouy") '*' else c)
        }
        return even.take(4)
    }
}
