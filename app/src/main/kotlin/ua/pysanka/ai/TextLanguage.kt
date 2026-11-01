package ua.pysanka.ai

/**
 * Pysanka: the language of a text, worked out on the phone from its letters, so the model is told
 * plainly which language to answer in (a small model told "the language of the text" sometimes
 * answers in English).
 *
 * Only clear cases: Ukrainian (Cyrillic with і, ї, є or ґ, or without the Russian ы, э, ъ, ё),
 * Polish, German, French and Spanish by their own letters, English by its common words. Anything
 * else is null, and the model is asked to keep the language of the text.
 */
object TextLanguage {
    const val UKRAINIAN = "Ukrainian"
    const val ENGLISH = "English"

    private val ENGLISH_WORDS = setOf(
        "the", "and", "is", "are", "to", "of", "you", "it", "in", "for", "that", "with", "this", "be", "on", "not",
        "have", "was", "will", "we", "i", "can", "my", "your", "at", "please", "thanks", "hi", "hello",
    )

    fun of(text: String): String? {
        var cyrillic = 0
        var latin = 0
        for (c in text) {
            if (!c.isLetter()) continue
            when (Character.UnicodeScript.of(c.code)) {
                Character.UnicodeScript.CYRILLIC -> cyrillic++
                Character.UnicodeScript.LATIN -> latin++
                else -> {}
            }
        }
        if (cyrillic + latin < 3) return null
        val lower = text.lowercase()
        if (cyrillic >= latin) {
            val ukrainian = lower.any { it in "іїєґ" }
            // Russian, Bulgarian, Serbian, Macedonian: left to the model
            val russian = lower.any { it in "ыэъёђјљњћџѓќѕ" }
            return if (ukrainian || !russian) UKRAINIAN else null
        }
        return when {
            lower.any { it in "ąęłńśźż" } -> "Polish"
            lower.any { it in "äöüß" } -> "German"
            lower.any { it in "ñ¿¡" } -> "Spanish"
            lower.any { it in "çèêëàâîôûœ" } -> "French"
            else -> {
                val words = lower.split(Regex("[^a-z']+")).filter { it.isNotEmpty() }
                // A few common English words among the first ones: English
                val common = words.take(60).count { it in ENGLISH_WORDS }
                if (words.isNotEmpty() && (common >= 2 || common * 5 >= words.size.coerceAtMost(60))) ENGLISH else null
            }
        }
    }
}
