package ua.pysanka.typing

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LayoutSwitchTest : FunSpec({
    test("English keys to Ukrainian") {
        LayoutSwitch.convert("ghbdsn") shouldBe "привіт"
        // On the Ukrainian layout the comma is where "?" is on the English one
        LayoutSwitch.convert("Ghbdsn? cdsnt!") shouldBe "Привіт, світе!"
        LayoutSwitch.convert("]ofr") shouldBe "їщак"
        LayoutSwitch.convert("'ytn") shouldBe "єнет"
    }
    test("Ukrainian keys to English") {
        LayoutSwitch.convert("руддщ") shouldBe "hello"
        LayoutSwitch.convert("Руддщ цщкдв") shouldBe "Hello world"
    }
    test("there and back") {
        val s = "Привіт, як справи. Їжак і ганок"
        LayoutSwitch.convert(LayoutSwitch.convert(s)) shouldBe s
    }
})
