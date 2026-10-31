package ua.pysanka.settings

/**
 * Pysanka: the languages shown first when adding a keyboard language: Ukrainian and English, then
 * the countries where most Ukrainians live and write (neighbours and the EU, western and northern
 * Europe), Turkish and Georgian. All other languages follow in alphabetical order.
 */
object PopularLanguages {
    private val order = listOf(
        "uk", "en-US", "en-UK",
        "pl", "de-DE", "cs", "sk", "ro", "hu", "lt-LT", "lv-LV", "et-EE",
        "it-IT", "es-ES", "fr-FR", "pt-PT", "nl-NL",
        "sv-SE", "nb-NO", "da-DK", "fi-FI",
        "tr", "ka-GE",
    )

    fun rank(tag: String): Int = order.indexOfFirst { it.equals(tag, ignoreCase = true) }.let { if (it < 0) order.size else it }
}
