package ua.pysanka.ai

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RedactTest : FunSpec({
    test("e-mail, phone, card, IBAN") {
        Redact.apply("Пишіть на name.surname@mail.example.ua або дзвоніть +380 67 123 45 67.") shouldBe
            "Пишіть на [пошта] або дзвоніть [телефон]."
        Redact.apply("Картка 4111 1111 1111 1111, рахунок UA213223130000026007233566001.") shouldBe
            "Картка [картка], рахунок [рахунок]."
        Redact.apply("Call me at 067-123-45-67") shouldBe "Call me at [phone]"
    }
    test("dates, years, prices and order numbers stay") {
        val s = "Зустріч 31.10.2026 о 18:00, ціна 1 250 грн, замовлення 12345."
        Redact.apply(s) shouldBe s
        val t = "Бюджет 125 000 000 грн на 2019 2020 2021 роки, накладна 20450012345678."
        Redact.apply(t) shouldBe t
    }
    test("phone in brackets") {
        Redact.apply("Тел. (044) 123-45-67") shouldBe "Тел. [телефон]"
    }
    test("a number that only looks like a card (bad check digit) stays") {
        Redact.apply("Код 1234 5678 9012 3456") shouldBe "Код 1234 5678 9012 3456"
    }
})
