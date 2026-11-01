package ua.pysanka.typing

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.IOException

class PersonalWordsTest : FunSpec({
    val list = mapOf("привіт" to 200, "привести" to 120, "зустріч" to 150, "борщ" to 90, "борщик" to 40)
    val s = WordSuggester(list, KeyNeighbours.forLanguage("uk"))

    test("only things that look like words are learned") {
        PersonalWords.isLearnable("сонечко") shouldBe true
        PersonalWords.isLearnable("п'ятниця") shouldBe true
        PersonalWords.isLearnable("пʼять") shouldBe true
        PersonalWords.isLearnable("будь-що") shouldBe true
        PersonalWords.isLearnable("а") shouldBe false
        PersonalWords.isLearnable("abc123") shouldBe false
        PersonalWords.isLearnable("mail@host") shouldBe false
        PersonalWords.isLearnable("пpивiт") shouldBe false // Latin letters mixed in
        PersonalWords.isLearnable("-слово") shouldBe false
    }

    test("an own word is offered after KNOWN uses, and never corrected") {
        val own = PersonalWords()
        repeat(PersonalWords.KNOWN - 1) { own.learn("борщуля", insisted = false, midSentence = true, day = 1) }
        s.suggest("борщу", 5, own = own).none { it.word == "борщуля" } shouldBe true
        own.learn("борщуля", insisted = false, midSentence = true, day = 1)
        s.suggest("борщу", 5, own = own).first().word shouldBe "борщуля"
        s.suggest("борщуля", 5, own = own).none { it.autoCorrect } shouldBe true
        s.isOwn("борщуля", own) shouldBe true
        s.isOwn("борщ", own) shouldBe false
    }

    test("auto-correction goes only into a list word, never into a learned one") {
        val own = PersonalWords()
        repeat(PersonalWords.KNOWN) { own.learn("борщуля", insisted = false, midSentence = true, day = 1) }
        s.suggest("борщул", 3, own = own).none { it.autoCorrect } shouldBe true
    }

    test("a word whose small letters are odd is not learned (and never spoils the file)") {
        val own = PersonalWords()
        own.learn("İstanbul", insisted = true, midSentence = true, day = 1) shouldBe false
        own.learn("кава", insisted = true, midSentence = true, day = 1)
        PersonalWords.decode(own.encode()).count("кава") shouldBe PersonalWords.KNOWN
    }

    test("a word the person insists on is known at once") {
        val own = PersonalWords()
        // Without it, «привтт» would become «привіт»
        s.suggest("привтт", 3).first().autoCorrect shouldBe true
        own.learn("привтт", insisted = true, midSentence = true, day = 1)
        s.suggest("привтт", 3, own = own).none { it.autoCorrect } shouldBe true
    }

    test("a list word used often is offered higher") {
        val own = PersonalWords()
        s.suggest("бор", 2).map { it.word } shouldBe listOf("борщ", "борщик")
        repeat(10) { own.learn("борщик", insisted = false, midSentence = true, day = 1) }
        s.suggest("бор", 2, own = own).map { it.word } shouldBe listOf("борщик", "борщ")
    }

    test("a name keeps its capital only when typed so inside a sentence") {
        val own = PersonalWords()
        own.learn("Ярина", insisted = true, midSentence = true, day = 1)
        s.suggest("яри", 1, own = own).map { it.word } shouldBe listOf("Ярина")
        val other = PersonalWords()
        other.learn("Сонячно", insisted = true, midSentence = false, day = 1)
        s.suggest("сон", 1, own = other).map { it.word } shouldBe listOf("сонячно")
        val caps = PersonalWords()
        caps.learn("НАТО", insisted = true, midSentence = true, day = 1)
        caps.display("нато") shouldBe null
    }

    test("undo and forget") {
        val own = PersonalWords()
        own.learn("кавуся", insisted = true, midSentence = true, day = 1)
        own.unlearn("кавуся")
        own.count("кавуся") shouldBe PersonalWords.KNOWN - 1
        own.forget("Кавуся") shouldBe true
        own.count("кавуся") shouldBe 0
    }

    test("at most the given number of words; the least used go first") {
        val own = PersonalWords(max = 3)
        repeat(3) { own.learn("часте", false, true, 1) }
        own.learn("рідке", false, true, 1)
        own.learn("старе", false, true, 0)
        own.learn("нове", false, true, 2)
        own.size shouldBe 3
        own.count("старе") shouldBe 0
        own.count("часте") shouldBe 3
    }

    test("written and read back; anything else is refused") {
        val own = PersonalWords()
        own.learn("Ярина", true, true, 5)
        own.learn("п’ятниця", false, true, 5)
        val back = PersonalWords.decode(own.encode())
        back.count("ярина") shouldBe PersonalWords.KNOWN
        back.display("ярина") shouldBe "Ярина"
        back.count("п'ятниця") shouldBe 1
        shouldThrow<IOException> { PersonalWords.decode(byteArrayOf(0, 0, 0, 9)) }
        shouldThrow<IOException> { PersonalWords.decode(own.encode() + 1) }
    }

    test("a file from before pairs (format 1) is still read") {
        val bytes = java.io.ByteArrayOutputStream().also { b ->
            java.io.DataOutputStream(b).use { out ->
                out.writeInt(1); out.writeInt(1)
                out.writeUTF("кава"); out.writeUTF(""); out.writeInt(3); out.writeInt(1)
            }
        }.toByteArray()
        PersonalWords.decode(bytes).count("кава") shouldBe 3
    }

    test("the next word: offered after PAIR_KNOWN uses of the pair, written and read back") {
        val own = PersonalWords()
        repeat(PersonalWords.PAIR_KNOWN - 1) { own.learnPair("добрий", "ранок", 1) }
        repeat(PersonalWords.KNOWN) { own.learn("ранок", false, true, 1) }
        s.predict("добрий", 3, own) shouldBe emptyList()
        own.learnPair("Добрий", "ранок", 1)
        s.predict("добрий", 3, own) shouldBe listOf("ранок")
        PersonalWords.decode(own.encode()).next("добрий", 3) shouldBe listOf("ранок")
        own.forgetPair("добрий", "ранок") shouldBe true
        s.predict("добрий", 3, own) shouldBe emptyList()
    }

    test("the word before lifts what usually follows it (a pair used PAIR_KNOWN times)") {
        val own = PersonalWords()
        s.suggest("при", 2, own = own, previous = "дуже").map { it.word } shouldBe listOf("привіт", "привести")
        own.learnPair("дуже", "привести", 1)
        s.suggest("при", 2, own = own, previous = "дуже").map { it.word } shouldBe listOf("привіт", "привести")
        repeat(PersonalWords.PAIR_KNOWN - 1) { own.learnPair("дуже", "привести", 1) }
        s.suggest("при", 2, own = own, previous = "дуже").map { it.word } shouldBe listOf("привести", "привіт")
    }

    test("a pair never makes an auto-correction by itself") {
        val own = PersonalWords()
        repeat(5) { own.learnPair("дуже", "привести", 1) }
        // «привесит» is one swap from «привести»; the pair alone must not turn it into a correction
        val plain = s.suggest("привесит", 3).first().autoCorrect
        s.suggest("привесит", 3, own = own, previous = "дуже").first().autoCorrect shouldBe plain
    }

    test("forgetting a word forgets its pairs") {
        val own = PersonalWords()
        own.learnPair("мій", "борщ", 1)
        own.learnPair("борщ", "смачний", 1)
        own.forget("борщ") shouldBe true
        own.pairCount shouldBe 0
    }

    test("the word before the cursor, only in the same sentence") {
        PreviousWord.of("Привіт, мій ") shouldBe "мій"
        PreviousWord.of("мій  ") shouldBe "мій"
        PreviousWord.of("Кінець. ") shouldBe null
        PreviousWord.of("привіт,") shouldBe null
        PreviousWord.of("ab12 ") shouldBe null
        PreviousWord.of("рядок\n") shouldBe null
        PreviousWord.of("") shouldBe null
        PreviousWord.of("x".repeat(100) + " ") shouldBe null
        PreviousWord.of("Привіт мій", spaceComing = true) shouldBe "мій"
        PreviousWord.of("Привіт мій") shouldBe null
    }
})
