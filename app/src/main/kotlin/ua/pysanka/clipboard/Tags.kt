package ua.pysanka.clipboard

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType

/** Pysanka: one of the owner's own tags, as the interface shows it. */
data class TagView(val id: Long, val name: String, val color: Int)

/**
 * Pysanka: tag colours, the same calm palette as Mandat's. A colour is never the only sign: every
 * chip shows the tag's name, and each colour has a name of its own for screen readers.
 */
object TagColors {
    val colors = listOf(
        Color(0xFF378ADD), // blue
        Color(0xFF1D9E75), // teal
        Color(0xFFD85A30), // coral
        Color(0xFF7F77DD), // purple
        Color(0xFF639922), // green
        Color(0xFFBA7517), // amber
        Color(0xFFD4537E), // pink
        Color(0xFF888780), // grey
    )

    @StringRes
    val names = listOf(
        R.string.pysanka__tag_color__blue,
        R.string.pysanka__tag_color__teal,
        R.string.pysanka__tag_color__coral,
        R.string.pysanka__tag_color__purple,
        R.string.pysanka__tag_color__green,
        R.string.pysanka__tag_color__amber,
        R.string.pysanka__tag_color__pink,
        R.string.pysanka__tag_color__grey,
    )

    fun color(index: Int): Color = colors[Math.floorMod(index, colors.size)]
}

/**
 * Pysanka: tags the keyboard sees by itself, from what an item holds. Nothing is stored for them;
 * they are worked out each time, on the phone.
 */
enum class AutoTag(@StringRes val label: Int) {
    IMAGE(R.string.pysanka__auto_tag__image),
    LINK(R.string.pysanka__auto_tag__link),
    PHONE(R.string.pysanka__auto_tag__phone),
    CODE(R.string.pysanka__auto_tag__code),
    EMAIL(R.string.pysanka__auto_tag__email);

    fun matches(item: ClipboardItem): Boolean {
        if (this == IMAGE) return item.type == ItemType.IMAGE
        val text = item.text ?: return false
        if (item.type != ItemType.TEXT || text.length > 2_000) return false
        return when (this) {
            LINK -> AutoTagPatterns.link.containsMatchIn(text)
            EMAIL -> AutoTagPatterns.email.containsMatchIn(text)
            CODE -> AutoTagPatterns.code.matches(text.trim())
            PHONE -> !AutoTagPatterns.code.matches(text.trim()) &&
                AutoTagPatterns.phone.findAll(text).any { AutoTagPatterns.digits(it.value) in 10..13 }
            IMAGE -> false
        }
    }
}

internal object AutoTagPatterns {
    val link = Regex("""(?i)\b(?:https?://|www\.)\S+""")
    val email = Regex("""(?i)\b[\w.+-]+@[\w-]+(?:\.[\w-]+)+\b""")

    /**
     * A one-time code: the whole item is 4–8 digits, maybe split in two ("123 456"). A bare year
     * (19xx, 20xx) is not taken for a code.
     */
    val code = Regex("""(?!(?:19|20)\d\d$)\d{4,8}|\d{3,4}[ -]\d{3,4}""")

    /**
     * A phone number: the whole item (or a line of it) starts with + or 0 or a bracket and has
     * 10–13 digits, with spaces, dashes or brackets between. Card numbers (16 digits), IBANs,
     * dates and times do not match.
     */
    val phone = Regex("""(?m)^\s*(?:\+|\(?0)[\d ()\-]{8,20}\s*$""")

    fun digits(s: String) = s.count { it.isDigit() }
}

/** Pysanka: what the tag slider is filtering by: nothing (all), an automatic tag or an own tag. */
sealed interface TagFilter {
    fun matches(item: ClipboardItem): Boolean

    data class Auto(val tag: AutoTag) : TagFilter {
        override fun matches(item: ClipboardItem) = tag.matches(item)
    }

    data class Own(val id: Long) : TagFilter {
        override fun matches(item: ClipboardItem) = id in item.tagIds()
    }
}
