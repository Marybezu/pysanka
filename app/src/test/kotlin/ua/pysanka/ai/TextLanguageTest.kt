package ua.pysanka.ai

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TextLanguageTest : FunSpec({
    test("the language of a text") {
        TextLanguage.of("На сторінці нашого омбудсмена сьогодні п'ять відео.") shouldBe "Ukrainian"
        TextLanguage.of("Привіт, як справи") shouldBe "Ukrainian"
        TextLanguage.of("Это текст на другом языке, ещё один.") shouldBe null
        TextLanguage.of("Send the report by Friday, please.") shouldBe "English"
        TextLanguage.of("Dzień dobry, proszę o raport.") shouldBe "Polish"
        TextLanguage.of("Schöne Grüße aus Berlin") shouldBe "German"
        TextLanguage.of("12345") shouldBe null
    }

    test("a change of style that changed almost nothing") {
        val text = "Вахтанг Кіпіані. На сторінці нашого омбудсмена сьогодні п'ять відео."
        TextChanges.tooLittle(AiAction.FRIENDLY, text, "Вахтанг Кіпіані: на сторінці нашого омбудсмена сьогодні п'ять відео!") shouldBe true
        TextChanges.tooLittle(AiAction.FRIENDLY, text, "Привіт! Уяви, у нашого омбудсмена сьогодні аж п'ять нових відео.") shouldBe false
        TextChanges.tooLittle(AiAction.SHORTER, text, text.dropLast(3)) shouldBe true
        TextChanges.tooLittle(AiAction.SHORTER, text, "Омбудсмен сьогодні виклав п'ять відео.") shouldBe false
    }
})
