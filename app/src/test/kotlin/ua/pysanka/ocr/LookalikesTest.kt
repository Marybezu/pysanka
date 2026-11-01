package ua.pysanka.ocr

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LookalikesTest : FunSpec({
    test("a Latin-looking word in a Cyrillic line becomes Cyrillic") {
        Lookalikes.fix("Людвіг фон Mizec") shouldBe "Людвіг фон Мізес"
    }
    test("words with other Latin letters and Latin lines stay") {
        Lookalikes.fix("Видавництво WELL") shouldBe "Видавництво WELL"
        Lookalikes.fix("Pixel 10 Pro") shouldBe "Pixel 10 Pro"
        Lookalikes.fix("Hello Mac") shouldBe "Hello Mac"
    }
    test("a line of one word is left alone") {
        Lookalikes.fix("Mizec") shouldBe "Mizec"
    }
})
