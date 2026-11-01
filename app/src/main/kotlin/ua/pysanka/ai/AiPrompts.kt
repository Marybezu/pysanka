package ua.pysanka.ai

import androidx.annotation.StringRes
import dev.patrickgold.florisboard.R

/**
 * Pysanka: what the AI editor can do with a text, and how the work is done:
 * - [Kind.REWRITE]: the text is rewritten; a long one part by part, put back in its order.
 * - [Kind.SUMMARY]: a long text is summed up part by part, then the notes are summed up together.
 * - [Kind.WHOLE]: the text is turned into something new at once (a list, a letter…), so it must fit
 *   in one go ([AiJob.WHOLE_MAX]).
 * - [Kind.ADVICE]: like [Kind.WHOLE], but the answer is about the text, not a new text: it is only
 *   shown and copied, never put in the field.
 */
enum class AiAction(@StringRes val label: Int, val temperature: Double, val kind: Kind) {
    FIX(R.string.pysanka__ai__action_fix, 0.2, Kind.REWRITE),
    FIX_OCR(R.string.pysanka__ai__action_fix_ocr, 0.2, Kind.REWRITE),
    RUSSISMS(R.string.pysanka__ai__action_russisms, 0.2, Kind.REWRITE),
    SUMMARY(R.string.pysanka__ai__action_summary, 0.2, Kind.SUMMARY),
    TRANSLATE(R.string.pysanka__ai__action_translate, 0.2, Kind.REWRITE),
    FRIENDLY(R.string.pysanka__ai__action_friendly, 0.6, Kind.REWRITE),
    FORMAL(R.string.pysanka__ai__action_formal, 0.5, Kind.REWRITE),
    SHORTER(R.string.pysanka__ai__action_shorter, 0.4, Kind.REWRITE),
    LIST(R.string.pysanka__ai__action_list, 0.2, Kind.WHOLE),
    TABLE(R.string.pysanka__ai__action_table, 0.2, Kind.WHOLE),
    NOTES(R.string.pysanka__ai__action_notes, 0.3, Kind.WHOLE),
    LETTER(R.string.pysanka__ai__action_letter, 0.6, Kind.WHOLE),
    REPLIES(R.string.pysanka__ai__action_replies, 0.7, Kind.WHOLE),
    TONE(R.string.pysanka__ai__action_tone, 0.3, Kind.ADVICE),
    REDACT(R.string.pysanka__ai__action_redact, 0.1, Kind.REWRITE);

    enum class Kind { REWRITE, SUMMARY, WHOLE, ADVICE }

    /** Whether the answer may replace the text in the field (advice is only read and copied). */
    val replaces: Boolean get() = kind != Kind.ADVICE
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
        "You are a careful text editor inside a phone keyboard. You get a text written or received by the person. " +
            "Answer with ONLY the result: no introduction, no explanation, no quotes, no notes, no markdown. " +
            "Never add facts, names, numbers or dates that are not in the text. " +
            "If the text is in Ukrainian, write correct modern Ukrainian, never Russian words, Russian spelling or surzhyk."

    private const val KEEP_LANGUAGE = "Write in the language of the text."
    private const val POINTS = "Start each point with \"• \" on its own line."

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
        AiAction.SUMMARY -> "$KEEP_LANGUAGE Summarise the text in two to five short points: the main things a reader " +
            "must know. $POINTS Copy names, numbers and dates exactly as they are written in the text. " +
            "Do not add opinions, conclusions or anything the text does not say."
        AiAction.TRANSLATE -> "Translate the text into ${target.english}. Keep the meaning, tone, names, numbers and " +
            "line breaks. Write natural ${target.english}, not word by word."
        AiAction.LIST -> "$KEEP_LANGUAGE Turn the text into a clear list: one item per line, short and parallel. " +
            "$POINTS Keep every fact; add nothing."
        AiAction.TABLE -> "$KEEP_LANGUAGE Arrange the information of the text as a table in plain text: the first " +
            "line is the header, columns are separated by \" | \", one row per line. Choose the columns from the text " +
            "itself. Keep every fact exactly; leave a cell empty rather than guess."
        AiAction.NOTES -> "$KEEP_LANGUAGE The text is the person's rough notes. Put them in order: group related points " +
            "under short headings (a heading on its own line), merge repeats, keep every fact and the person's own " +
            "words where possible. $POINTS"
        AiAction.LETTER -> "The text describes a message the person wants to write: to whom, about what, maybe the tone. " +
            "Write that message, ready to send, in the language of the description, natural and polite. Use only facts " +
            "from the description; where a detail is missing, put a placeholder in square brackets, like [дата] or [date]."
        AiAction.REPLIES -> "The text is a message the person received. Write three short possible replies in the language " +
            "of the message: one that agrees, one that politely declines, one that asks to clarify. Put a blank line " +
            "between them and start each with a short label in that language and a colon (for example «Так:», «Ні:», " +
            "«Уточнити:»). Do not invent facts about the person."
        AiAction.TONE -> "Do not rewrite the text. In the language of the text, say in two to four short sentences how " +
            "the reader will probably take it: its tone, anything that may sound harsh, cold, pushy or unclear, and one " +
            "concrete improvement."
        AiAction.REDACT -> "$KEEP_LANGUAGE Replace personal data with placeholders in square brackets, written in the " +
            "language of the text: names of people, phone numbers, e-mail addresses, home addresses, card and account " +
            "numbers, document numbers, dates of birth (in Ukrainian: [ім'я], [телефон], [пошта], [адреса], [картка], " +
            "[рахунок], [документ], [дата народження]). Change nothing else."
    }

    /** For a part of a long text being summed up: its notes, to be summed up together later. */
    fun summaryPart(part: Int, parts: Int): String = BASE + " " + KEEP_LANGUAGE +
        " This is part $part of $parts of a longer text. List its main points in up to five short points. $POINTS " +
        "Copy names, numbers and dates exactly as written. Add nothing."

    /** For the notes of all the parts: the summary of the whole text. */
    fun summaryJoin(): String = BASE + " " + KEEP_LANGUAGE +
        " You get notes on the consecutive parts of one long text. Write the summary of the whole text in three to " +
        "six short points: the main things a reader must know, in order. $POINTS Copy names, numbers and dates " +
        "exactly as they are in the notes. Add nothing that is not in the notes."

    fun user(text: String): String = "Text:\n$text"

    /**
     * Removes what small models sometimes wrap the answer in: code fences, "Here is…:", quotes —
     * but only what the person's own [original] text did not have itself.
     */
    fun clean(raw: String, original: String): String {
        var s = raw.trim()
        // An opening fence with an optional language tag on its own line, or a bare fence
        val fence = Regex("^```(?:[A-Za-z-]*[ \\t]*\\r?\\n)?")
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
