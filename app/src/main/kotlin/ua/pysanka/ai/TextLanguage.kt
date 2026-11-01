package ua.pysanka.ai

/**
 * Pysanka: the language of a text, worked out on the phone from its letters, so the model is told
 * plainly which language to answer in (a small model told "the language of the text" sometimes
 * answers in English).
 *
 * Only unmistakable cases: Ukrainian by its own letters (і, ї, є, ґ) or apostrophe; Polish, German
 * and Spanish by letters no other popular language shares (ą ę ł ś ź ż ń, ß, ñ ¿ ¡); English by
 * plain letters and enough of its common words. Anything else — Russian, a mix of alphabets,
 * Italian, Dutch, Turkish… — is null, and the model is asked to keep the language of the text
 * (naming a wrong language would make it translate).
 */
object TextLanguage {
    const val UKRAINIAN = "Ukrainian"
    const val ENGLISH = "English"

    /** Common English words that Dutch, German or Danish do not share (no "is", "in", "we"…). */
    private val ENGLISH_WORDS = setOf(
        "the", "and", "are", "to", "of", "you", "it", "for", "that", "with", "this", "be", "on", "not",
        "have", "was", "will", "can", "my", "your", "at", "please", "thanks", "hi", "hello", "what",
        "do", "does", "would", "could", "should", "me", "our", "they", "there", "from", "i'm", "it's",
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
        val letters = cyrillic + latin
        if (letters < 3) return null
        // Two alphabets mixed (a name or a phrase in another language is fine)
        if (minOf(cyrillic, latin) * 4 > letters) return null
        val lower = text.lowercase()
        if (cyrillic > latin) return if (lower.any { it in "іїєґ'’ʼ" }) UKRAINIAN else null
        return when {
            lower.any { it in "ąęłśźżń" } -> "Polish"
            'ß' in lower -> "German"
            lower.any { it in "ñ¿¡" } -> "Spanish"
            // Other letters with marks: some other language
            lower.any { it.isLetter() && it !in 'a'..'z' } -> null
            else -> {
                val words = lower.split(Regex("[^a-z']+")).filter { it.isNotEmpty() }.take(60)
                val common = words.count { it in ENGLISH_WORDS }
                // English text is about a third such words; Dutch or Danish shares only a few
                if (words.size >= 3 && common * 4 >= words.size) ENGLISH else null
            }
        }
    }
}
