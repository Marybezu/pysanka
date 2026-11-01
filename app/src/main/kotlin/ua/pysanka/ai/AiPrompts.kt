package ua.pysanka.ai

import androidx.annotation.StringRes
import dev.patrickgold.florisboard.R

/** Pysanka: what the AI editor can do with a text. */
enum class AiAction(@StringRes val label: Int, val temperature: Double) {
    FIX(R.string.pysanka__ai__action_fix, 0.2),
    FIX_OCR(R.string.pysanka__ai__action_fix_ocr, 0.2),
    RUSSISMS(R.string.pysanka__ai__action_russisms, 0.2),
    FRIENDLY(R.string.pysanka__ai__action_friendly, 0.6),
    FORMAL(R.string.pysanka__ai__action_formal, 0.5),
    SHORTER(R.string.pysanka__ai__action_shorter, 0.4),
    SUMMARY(R.string.pysanka__ai__action_summary, 0.3),
    TRANSLATE(R.string.pysanka__ai__action_translate, 0.2),
}

/** Languages to translate into. */
enum class AiLanguage(@StringRes val label: Int, val english: String) {
    UK(R.string.pysanka__ai__lang_uk, "Ukrainian"),
    EN(R.string.pysanka__ai__lang_en, "English"),
    PL(R.string.pysanka__ai__lang_pl, "Polish"),
    DE(R.string.pysanka__ai__lang_de, "German"),
    FR(R.string.pysanka__ai__lang_fr, "French"),
    ES(R.string.pysanka__ai__lang_es, "Spanish"),
}

/**
 * The instructions for each action. In English (the model follows them best), with the rule that
 * the answer keeps the text's own language and is only the new text, without comments or quotes.
 * For Ukrainian the model is told plainly to write without Russian words or surzhyk.
 */
internal object AiPrompts {
    private const val BASE =
        "You are a careful text editor inside a phone keyboard. You get a text written by the person. " +
            "Answer with ONLY the resulting text: no introduction, no explanation, no quotes, no notes, no markdown. " +
            "Never add facts, names or numbers that are not in the text. " +
            "If the text is in Ukrainian, write correct modern Ukrainian, never Russian words, Russian spelling or surzhyk."

    private const val KEEP_LANGUAGE = "Keep the language of the text."

    fun system(action: AiAction, target: AiLanguage): String = BASE + " " + when (action) {
        AiAction.FIX -> "$KEEP_LANGUAGE Correct spelling, grammar and punctuation. Keep the wording, the meaning, " +
            "the tone and the line breaks; change only what is wrong."
        AiAction.FIX_OCR -> "$KEEP_LANGUAGE The text was recognised from a photo and has recognition errors: " +
            "letters mixed up (for example Latin look-alikes in a Cyrillic word, or a digit inside a Cyrillic word such as 6 for б or 3 for з; real numbers stay as they are), " +
            "words joined together or split apart, words broken across lines with a hyphen, lines broken in the " +
            "middle of a sentence. Fix only such errors. Join the lines of one paragraph; keep breaks between " +
            "paragraphs and list items. Do not rephrase and do not change anything that is already right."
        AiAction.RUSSISMS -> "The text is in Ukrainian. Replace Russianisms, calques from Russian and surzhyk with " +
            "natural Ukrainian words and phrases (for example «приймати участь» → «брати участь», «на протязі» → " +
            "«протягом», «вірно» → «правильно» where it means correct). Change nothing else."
        AiAction.FRIENDLY -> "$KEEP_LANGUAGE Rewrite the text in a warm, friendly, natural tone, as a person would " +
            "write to someone they know. Keep the meaning and every fact; about the same length."
        AiAction.FORMAL -> "$KEEP_LANGUAGE Rewrite the text in a polite, clear business tone. Keep the meaning and " +
            "every fact; about the same length."
        AiAction.SHORTER -> "$KEEP_LANGUAGE Make the text noticeably shorter and clearer. Keep every important fact " +
            "and the tone."
        AiAction.SUMMARY -> "$KEEP_LANGUAGE Summarise the text: the main points in one to four short sentences, or a " +
            "short list if there are several separate points."
        AiAction.TRANSLATE -> "Translate the text into ${target.english}. Keep the meaning, tone, names, numbers and " +
            "line breaks. Write natural ${target.english}, not word by word."
    }

    fun user(action: AiAction, text: String, target: AiLanguage): String = "Text:\n$text"

    /**
     * Removes what small models sometimes wrap the answer in: code fences, "Here is…:", quotes —
     * but only what the person's own [original] text did not have itself.
     */
    fun clean(raw: String, original: String): String {
        var s = raw.trim()
        val fence = Regex("^```[A-Za-z-]*\\s*\\n?")
        if (fence.containsMatchIn(s) && !original.trimStart().startsWith("```")) {
            s = s.replaceFirst(fence, "").removeSuffix("```").trim()
        }
        val firstLine = s.substringBefore('\n')
        if (s.contains('\n') && firstLine.endsWith(":") && firstLine.length < 80 &&
            !original.trimStart().startsWith(firstLine) &&
            Regex("(?i)^(here|ось|виправлен|переклад|текст|result|summary|підсумок)").containsMatchIn(firstLine)
        ) {
            s = s.substringAfter('\n').trim()
        }
        val o = original.trim()
        val quotedOriginal = o.length >= 2 && ((o.first() == '"' && o.last() == '"') || (o.first() == '«' && o.last() == '»'))
        if (!quotedOriginal && s.length >= 2 && ((s.first() == '"' && s.last() == '"') || (s.first() == '«' && s.last() == '»'))) {
            val inner = s.substring(1, s.length - 1)
            if ('"' !in inner && '«' !in inner) s = inner.trim()
        }
        return s
    }
}
