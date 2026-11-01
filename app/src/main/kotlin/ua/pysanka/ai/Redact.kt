package ua.pysanka.ai

/**
 * Pysanka: personal data that can be found surely without the model (e-mail addresses, phone
 * numbers, card numbers, IBANs) is replaced after it, whatever it did, so "hide personal data"
 * never lets these through. Names and addresses are left to the model.
 */
object Redact {
    private val email = Regex("""[\p{L}\d._%+\-]+@[\p{L}\d.\-]+\.\p{L}{2,}""")
    private val iban = Regex("""\b[A-Z]{2}\d{2}(?:\s?[A-Z\d]){11,30}\b""")
    private val card = Regex("""\b\d(?:[ \-]?\d){12,18}\b""")
    /**
     * A phone number as people write it: with a + (international) or starting with 0 (national,
     * maybe in brackets). Sums like "125 000 000", years, order and waybill numbers do not start so.
     */
    private val phone = Regex("""(?<![\d\p{L}+(])(?:\+\d[\d ()\-]{7,}\d|\(?0\d[\d ()\-]{6,}\d)(?![\d\p{L}])""")

    fun apply(text: String): String {
        // The text's own language, not counting the addresses themselves
        val words = email.replace(text, " ")
        val cyrillic = words.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.CYRILLIC }
        val latin = words.count { it in 'a'..'z' || it in 'A'..'Z' }
        val uk = cyrillic >= latin
        fun label(ukr: String, en: String) = if (uk) "[$ukr]" else "[$en]"
        var s = email.replace(text, label("пошта", "email"))
        s = iban.replace(s, label("рахунок", "account"))
        s = card.replace(s) { m -> if (luhn(m.value)) label("картка", "card") else m.value }
        s = phone.replace(s) { m -> if (m.value.count { it.isDigit() } in 10..15) label("телефон", "phone") else m.value }
        return s
    }

    private fun luhn(s: String): Boolean {
        val d = s.filter { it.isDigit() }
        if (d.length !in 13..19) return false
        var sum = 0
        for ((i, c) in d.reversed().withIndex()) {
            var n = c - '0'
            if (i % 2 == 1) { n *= 2; if (n > 9) n -= 9 }
            sum += n
        }
        return sum % 10 == 0
    }
}
