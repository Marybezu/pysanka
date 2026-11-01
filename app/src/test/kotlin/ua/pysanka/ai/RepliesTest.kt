package ua.pysanka.ai

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RepliesTest : FunSpec({
    test("labelled replies with blank lines between them") {
        Replies.parse("Так: Так, буду о 10, дякую!\n\nНі: Завтра не вийде.\n\nУточнити: А де саме зустріч?") shouldBe listOf(
            Replies.Reply("Так", "Так, буду о 10, дякую!"),
            Replies.Reply("Ні", "Завтра не вийде."),
            Replies.Reply("Уточнити", "А де саме зустріч?"),
        )
    }
    test("one per line, with bold labels") {
        Replies.parse("**Yes:** Sure, see you.\n**No:** Sorry, I can't.") shouldBe listOf(
            Replies.Reply("Yes", "Sure, see you."),
            Replies.Reply("No", "Sorry, I can't."),
        )
    }
    test("a colon inside the reply is not a label") {
        Replies.parse("Буду о 10:00\n\nТак, буду: дякую") shouldBe listOf(
            Replies.Reply(null, "Буду о 10:00"),
            Replies.Reply(null, "Так, буду: дякую"),
        )
    }
})
