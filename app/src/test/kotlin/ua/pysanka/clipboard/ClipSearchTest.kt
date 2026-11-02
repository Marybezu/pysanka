package ua.pysanka.clipboard

import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ClipSearchTest : FunSpec({
    fun clip(text: String, tags: List<Long> = emptyList(), sensitive: Boolean = false) =
        ClipboardItem.text(text).copy(isSensitive = sensitive).withTagIds(tags)

    val tags = listOf(TagView(1, "Робота", 0), TagView(2, "Адреси", 1))

    test("every word must be found, in any letters, in the text or the tags") {
        val items = listOf(
            clip("Зустріч у п’ятницю о 10:00"),
            clip("вул. Хрещатик, 1", listOf(2)),
            clip("Звіт за жовтень", listOf(1)),
        )
        ClipSearch.results(ClipSearch.index(items, tags), "П'ЯТНИЦ").map { it.text } shouldBe listOf("Зустріч у п’ятницю о 10:00")
        ClipSearch.results(ClipSearch.index(items, tags), "адреси хрещ").map { it.text } shouldBe listOf("вул. Хрещатик, 1")
        ClipSearch.results(ClipSearch.index(items, tags), "робота жовтень").size shouldBe 1
        ClipSearch.results(ClipSearch.index(items, tags), "робота листопад").size shouldBe 0
        ClipSearch.results(ClipSearch.index(items, tags), "   ").size shouldBe 0
    }

    test("a password is found only by its tags, never by its text") {
        val items = listOf(clip("Secret123", listOf(1), sensitive = true))
        ClipSearch.results(ClipSearch.index(items, tags), "secret").size shouldBe 0
        ClipSearch.results(ClipSearch.index(items, tags), "робота").size shouldBe 1
    }

    test("a picture is found by the text recognised in it; words split at any space") {
        val picture = ClipboardItem(
            type = dev.patrickgold.florisboard.ime.clipboard.provider.ItemType.IMAGE, text = "Квитанція № 5",
            uri = null, creationTimestampMs = 0, isPinned = false, mimeTypes = listOf("image/png"),
        )
        ClipSearch.results(ClipSearch.index(listOf(picture), tags), "квитанц\u00A05").size shouldBe 1
    }

    test("at most MAX_RESULTS, in the order given") {
        val items = List(50) { clip("рядок $it") }
        val found = ClipSearch.results(ClipSearch.index(items, tags), "рядок")
        found.size shouldBe ClipSearch.MAX_RESULTS
        found.first().text shouldBe "рядок 0"
    }

    test("typing and deleting") {
        ClipSearch.type("x") // not searching: nothing happens
        ClipSearch.query.value shouldBe null
        ClipSearch.start()
        ClipSearch.type("ab")
        ClipSearch.typeWord("слово")
        ClipSearch.query.value shouldBe "ab слово"
        ClipSearch.type(" 👍")
        ClipSearch.deleteChar()
        ClipSearch.query.value shouldBe "ab слово "
        ClipSearch.deleteWord()
        ClipSearch.query.value shouldBe "ab "
        ClipSearch.type("x".repeat(500))
        ClipSearch.query.value!!.length shouldBe ClipSearch.MAX_CHARS
        ClipSearch.stop()
        ClipSearch.query.value shouldBe null
    }
})
