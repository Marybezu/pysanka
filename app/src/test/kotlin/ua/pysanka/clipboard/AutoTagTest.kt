package ua.pysanka.clipboard

import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AutoTagTest : FunSpec({
    fun tags(text: String) = AutoTag.entries.filter { it.matches(ClipboardItem.text(text)) }.toSet()

    test("links") {
        tags("see https://example.org/a?b=1") shouldBe setOf(AutoTag.LINK)
        tags("www.example.org") shouldBe setOf(AutoTag.LINK)
        tags("just text") shouldBe emptySet()
    }
    test("emails") {
        tags("write to name.surname+x@mail.example.ua") shouldBe setOf(AutoTag.EMAIL)
    }
    test("codes") {
        tags("483920") shouldBe setOf(AutoTag.CODE)
        tags(" 1234 ") shouldBe setOf(AutoTag.CODE)
        tags("123-456") shouldBe setOf(AutoTag.CODE)
        tags("12") shouldBe emptySet()
    }
    test("phone numbers") {
        tags("+380 (67) 123-45-67") shouldBe setOf(AutoTag.PHONE)
        tags("067 123 45 67") shouldBe setOf(AutoTag.PHONE)
        tags("Тел.\n+380671234567") shouldBe setOf(AutoTag.PHONE)
        tags("year 2026") shouldBe emptySet()
    }
    test("no false phones or codes") {
        tags("4111 1111 1111 1111") shouldBe emptySet()
        tags("UA21 3223 1300 0002 6007 2335 6600 1") shouldBe emptySet()
        tags("2026-10-31 14:30") shouldBe emptySet()
        tags("1234567890") shouldBe emptySet()
        tags("2026") shouldBe emptySet()
        tags("1999") shouldBe emptySet()
    }
    test("tag ids round trip") {
        val item = ClipboardItem.text("x").withTagIds(listOf(7L, 3L))
        item.tags shouldBe ",3,7,"
        item.tagIds() shouldBe setOf(3L, 7L)
        item.withTagIds(emptyList()).tags shouldBe ""
    }
})
