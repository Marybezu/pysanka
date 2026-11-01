package ua.pysanka.ai

/**
 * Pysanka: the possible replies the model wrote ([AiAction.REPLIES]), each on its own: separated by
 * blank lines, each starting with a short label and a colon («Так: …»). The label is shown, only
 * the reply itself goes into the field.
 */
internal object Replies {
    data class Reply(val label: String?, val text: String)

    /** At most this many characters before the colon count as a label («Уточнити:», "Clarify:"). */
    private const val LABEL_MAX = 20

    /** A short label, not the start of a reply that has a colon in it («Буду о 10:00»). */
    private fun isLabel(s: String) = s.trim('*', ' ').let { l ->
        l.isNotEmpty() && l.none { it.isDigit() || it == ',' || it == '\n' } && l.split(' ').size <= 3
    }

    private fun label(block: String): String? {
        val colon = block.indexOf(':')
        return block.takeIf { colon in 1..LABEL_MAX }?.substring(0, colon)?.takeIf { isLabel(it) }
    }

    fun parse(answer: String): List<Reply> {
        val blocks = answer.trim().split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
            // Replies one per line, without blank lines between them
            .flatMap { b -> b.lines().takeIf { ls -> ls.size > 1 && ls.all { label(it) != null } } ?: listOf(b) }
        return blocks.map { block ->
            val label = label(block)
            val text = if (label != null) block.substring(block.indexOf(':') + 1).trim() else block
            // Plain text, without the model's own bold markup
            Reply(label?.trim('*', ' '), text.trim('*', ' ', '\n'))
        }.filter { it.text.isNotEmpty() }
    }
}
