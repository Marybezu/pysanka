package ua.pysanka.ai

/**
 * Pysanka: splits a long text into parts the small model handles well, at paragraph boundaries
 * where possible, then at sentence ends, and only as a last resort in the middle of a sentence.
 * Each part keeps the whitespace that followed it, so the parts put back together give exactly the
 * original text (and rewritten parts keep the original paragraph breaks).
 */
object TextChunks {
    data class Part(val text: String, val after: String)

    private val paragraphBreak = Regex("""\n[ \t]*\n\s*""")
    private val sentenceEnd = Regex("""(?<=[.!?…])\s+""")

    fun split(text: String, max: Int): List<Part> {
        require(max > 0)
        // Pieces: paragraphs, then sentences of a paragraph too long, then hard cuts
        val pieces = ArrayList<Part>()
        for (p in cut(text, paragraphBreak)) {
            if (p.text.length <= max) { pieces.add(p); continue }
            val sentences = cut(p.text, sentenceEnd)
            for ((i, s) in sentences.withIndex()) {
                val after = if (i == sentences.lastIndex) p.after else s.after
                if (s.text.length <= max) { pieces.add(Part(s.text, after)); continue }
                var rest = s.text
                while (rest.length > max) {
                    // Rather at a space than inside a word
                    val at = rest.lastIndexOf(' ', max).takeIf { it > max / 2 } ?: max
                    pieces.add(Part(rest.substring(0, at), ""))
                    rest = rest.substring(at)
                }
                pieces.add(Part(rest, after))
            }
        }
        // Pack small pieces together up to max
        val parts = ArrayList<Part>()
        val sb = StringBuilder()
        var pendingAfter = ""
        for (p in pieces) {
            if (sb.isNotEmpty() && sb.length + pendingAfter.length + p.text.length > max) {
                parts.add(Part(sb.toString(), pendingAfter))
                sb.setLength(0)
            } else if (sb.isNotEmpty()) {
                sb.append(pendingAfter)
            }
            sb.append(p.text)
            pendingAfter = p.after
        }
        if (sb.isNotEmpty() || parts.isEmpty()) parts.add(Part(sb.toString(), pendingAfter))
        return parts
    }

    /** The start of [text] up to about [limit] characters, ending at a paragraph or sentence end. */
    fun prefix(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val head = text.substring(0, limit)
        val para = head.lastIndexOf("\n\n")
        if (para > limit / 2) return head.substring(0, para)
        val sentence = sentenceEnd.findAll(head).lastOrNull()?.range?.first
        if (sentence != null && sentence > limit / 2) return head.substring(0, sentence)
        return head.substring(0, head.lastIndexOf(' ').takeIf { it > limit / 2 } ?: limit)
    }

    private fun cut(text: String, by: Regex): List<Part> {
        val out = ArrayList<Part>()
        var start = 0
        for (m in by.findAll(text)) {
            out.add(Part(text.substring(start, m.range.first), m.value))
            start = m.range.last + 1
        }
        out.add(Part(text.substring(start), ""))
        return out
    }
}
