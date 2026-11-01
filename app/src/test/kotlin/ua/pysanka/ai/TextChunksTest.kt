package ua.pysanka.ai

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.ints.shouldBeLessThanOrEqual

class TextChunksTest : FunSpec({
    fun joined(parts: List<TextChunks.Part>) = parts.joinToString("") { it.text + it.after }

    test("parts put back together give the original text") {
        val text = (1..40).joinToString("\n\n") { "Абзац $it. Речення одне. Речення друге, трохи довше за перше!" }
        val parts = TextChunks.split(text, 300)
        joined(parts) shouldBe text
        parts.forEach { it.text.length shouldBeLessThanOrEqual 300 }
    }
    test("a long paragraph is cut at sentence ends, a long sentence at spaces") {
        val text = (1..30).joinToString(" ") { "Речення номер $it." } + "\n\n" + "слово ".repeat(200).trim()
        val parts = TextChunks.split(text, 120)
        joined(parts) shouldBe text
        parts.forEach { it.text.length shouldBeLessThanOrEqual 120 }
    }
    test("a text without full stops is cut at line breaks, whitespace kept between parts") {
        val text = (1..60).joinToString("\n") { "рядок номер $it без крапки" }
        val parts = TextChunks.split(text, 200)
        joined(parts) shouldBe text
        parts.forEach { it.text.startsWith("\n") shouldBe false }
        val leading = "\n\nПочаток після порожнього рядка."
        joined(TextChunks.split(leading, 100)) shouldBe leading
    }
    test("short text is one part") {
        TextChunks.split("Привіт", 100) shouldBe listOf(TextChunks.Part("Привіт", ""))
        TextChunks.split("", 100).size shouldBe 1
    }
    test("prefix ends at a paragraph or a sentence") {
        TextChunks.prefix("Перше речення. Друге речення. Третє речення.", 30) shouldBe "Перше речення. Друге речення."
        TextChunks.prefix("Абзац один.\n\nАбзац два, довгий-довгий.", 20) shouldBe "Абзац один."
        TextChunks.prefix("коротко", 100) shouldBe "коротко"
    }
})
