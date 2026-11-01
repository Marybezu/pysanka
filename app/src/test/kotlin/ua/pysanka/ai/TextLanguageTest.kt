package ua.pysanka.ai

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TextLanguageTest : FunSpec({
    test("Ukrainian, Polish, German, Spanish and English are named") {
        TextLanguage.of("На сторінці нашого омбудсмена сьогодні п'ять відео.") shouldBe "Ukrainian"
        TextLanguage.of("Привіт, як справи") shouldBe "Ukrainian"
        TextLanguage.of("Дякую, Олено, побачимося в п'ятницю о 10") shouldBe "Ukrainian"
        TextLanguage.of("Send the report by Friday, please.") shouldBe "English"
        TextLanguage.of("Dzień dobry, proszę o raport.") shouldBe "Polish"
        TextLanguage.of("Schöne Grüße aus Berlin, wir sehen uns morgen") shouldBe "German"
        TextLanguage.of("¿Nos vemos mañana?") shouldBe "Spanish"
    }

    test("anything not unmistakable is left to the model") {
        TextLanguage.of("Привет, как дела") shouldBe null
        TextLanguage.of("Это текст на другом языке, ещё один.") shouldBe null
        TextLanguage.of("Ciao, è tardi") shouldBe null
        TextLanguage.of("Görüşürüz yarın") shouldBe null
        TextLanguage.of("Het is in orde, we komen morgen") shouldBe null
        TextLanguage.of("Дякую, Steve, see you at the meeting") shouldBe null
        TextLanguage.of("12345") shouldBe null
    }
})
