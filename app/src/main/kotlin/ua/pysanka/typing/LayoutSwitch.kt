package ua.pysanka.typing

/**
 * Pysanka: text typed on the wrong layout, turned into what was meant: "ghbdsn" → "привіт",
 * "руддщ" → "hello". Key by key between the English (QWERTY) and Ukrainian (ЙЦУКЕН) layouts.
 * The direction follows the text: mostly Latin letters → Ukrainian, otherwise → English.
 */
object LayoutSwitch {
    // The apostrophe sits where "`" is; ґ where "\\" is (Ukrainian, enhanced)
    private const val EN = "qwertyuiop[]asdfghjkl;'zxcvbnm,./`\\QWERTYUIOP{}ASDFGHJKL:\"ZXCVBNM<>?|"
    private const val UK = "йцукенгшщзхїфівапролджєячсмитьбю.'ґЙЦУКЕНГШЩЗХЇФІВАПРОЛДЖЄЯЧСМИТЬБЮ,Ґ"

    private val toUk: Map<Char, Char> = EN.zip(UK).toMap()
    // Typographic apostrophes are typed with the same key
    private val toEn: Map<Char, Char> = UK.zip(EN).toMap() + mapOf('’' to '`', 'ʼ' to '`')

    /** The text on the other layout; the same text if there is nothing to change. */
    fun convert(text: String): String {
        val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        val cyrillic = text.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.CYRILLIC }
        val map = if (latin >= cyrillic) toUk else toEn
        return buildString(text.length) { for (c in text) append(map[c] ?: c) }
    }
}
