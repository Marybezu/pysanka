package ua.pysanka.ai

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FactCheckTest : FunSpec({
    val source = "У Києві 31.10.2026 відкрили пункт незламності. Сума допомоги — 1250,00 грн. " +
        "Олена Коваль розповіла, що пункт працює з 8:00 до 20:00."

    test("lines with facts from the text stay") {
        val s = "• Пункт незламності відкрили в Києві 31.10.2026.\n• Допомога — 1 250 грн.\n• Олена Коваль каже, що він працює з 8:00."
        FactCheck.check(source, s) shouldBe FactCheck.Result(s, 0, false)
    }
    test("a line with a number that is not in the text goes") {
        val s = "• Пункт відкрили в Києві.\n• Допомога — 5000 грн."
        FactCheck.check(source, s) shouldBe FactCheck.Result("• Пункт відкрили в Києві.", 1, false)
    }
    test("a line with a name that is not in the text goes") {
        val s = "• Пункт відкрили в Києві.\n• Про це розповів Петро Шевчук."
        FactCheck.check(source, s) shouldBe FactCheck.Result("• Пункт відкрили в Києві.", 1, false)
    }
    test("list numbers and words at the start of a sentence do not count") {
        val s = "1. Відкрили пункт.\n2. Сума 1250 грн."
        FactCheck.check(source, s).removed shouldBe 0
    }
    test("shortened dates, ranges with a dash, numbers listed with commas") {
        val src2 = "Подія 31.10.2026, сезон 2024–2025, ціни 100 і 200 грн."
        FactCheck.check(src2, "• 31.10: подія.\n• Сезон 2024-2025.\n• Ціни 100, 200 грн.").removed shouldBe 0
    }
    test("if nothing would be left, the text stays and is marked") {
        val s = "• Допомога 9999 грн."
        FactCheck.check(source, s) shouldBe FactCheck.Result(s, 1, true)
    }
})
