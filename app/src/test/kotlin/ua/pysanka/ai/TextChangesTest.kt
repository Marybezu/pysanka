package ua.pysanka.ai

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TextChangesTest : FunSpec({
    val text = "Вахтанг Кіпіані пише, що на сторінці нашого омбудсмена сьогодні п'ять нових відео."

    test("a change of style that changed almost nothing") {
        TextChanges.tooLittle(AiAction.FRIENDLY, text, "Вахтанг Кіпіані пише: на сторінці нашого омбудсмена сьогодні п'ять нових відео!") shouldBe true
        TextChanges.tooLittle(AiAction.FRIENDLY, text, "Привіт! Уяви, у нашого омбудсмена сьогодні аж п'ять нових відео — так пише Кіпіані.") shouldBe false
    }

    test("shorter must really be shorter") {
        TextChanges.tooLittle(AiAction.SHORTER, text, text.dropLast(3)) shouldBe true
        TextChanges.tooLittle(AiAction.SHORTER, text, "Кіпіані: в омбудсмена п'ять нових відео.") shouldBe false
    }

    test("a short text is never judged (it may need no change)") {
        TextChanges.tooLittle(AiAction.FORMAL, "Дякую", "Дякую.") shouldBe false
        TextChanges.tooLittle(AiAction.SHORTER, "Так.", "Так.") shouldBe false
    }
})
