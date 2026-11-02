package ua.pysanka.ai

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class HashtagsTest : FunSpec({
    test("only well-formed hashtags, each once, at most MAX, under the text as it is") {
        Hashtags.of("Ось: #Київ, #київ #кава_зранку # #123 #Осінь2026 ##x **#Львів**") shouldBe
            listOf("#Київ", "#кава_зранку", "#Осінь2026", "#Львів")
        Hashtags.of((1..20).joinToString(" ") { "#тег$it" }).size shouldBe Hashtags.MAX
        Hashtags.append("Ранкова кава.  \n", "#кава #ранок") shouldBe "Ранкова кава.\n\n#кава #ранок"
    }

    test("ones the text has already, or none at all") {
        Hashtags.of("#кава #ранок", "Кава #Кава") shouldBe listOf("#ранок")
        Hashtags.append("Текст", "кава, ранок") shouldBe ""
    }
})
