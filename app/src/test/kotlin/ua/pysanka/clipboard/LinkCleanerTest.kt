package ua.pysanka.clipboard

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LinkCleanerTest : FunSpec({
    fun c(s: String) = LinkCleaner.clean(s)

    test("utm and click ids go, the rest stays in order") {
        c("https://example.org/a?id=5&utm_source=x&utm_medium=y&fbclid=Z1&page=2") shouldBe "https://example.org/a?id=5&page=2"
        c("https://example.org/a?gclid=1") shouldBe "https://example.org/a"
        c("https://example.org/a?gclid=1#part") shouldBe "https://example.org/a#part"
    }
    test("site rules apply only on their sites") {
        c("https://youtu.be/abc123?si=XYZ") shouldBe "https://youtu.be/abc123"
        c("https://www.youtube.com/watch?v=abc&si=XYZ&t=42") shouldBe "https://www.youtube.com/watch?v=abc&t=42"
        c("https://x.com/user/status/1?s=20&t=abc") shouldBe "https://x.com/user/status/1"
        c("https://open.spotify.com/track/1?si=abc") shouldBe "https://open.spotify.com/track/1"
        // "s" and "t" are real parameters elsewhere
        c("https://example.org/search?s=kyiv&t=3") shouldBe "https://example.org/search?s=kyiv&t=3"
        c("https://www.instagram.com/p/ABC/?igsh=MWx") shouldBe "https://www.instagram.com/p/ABC/"
    }
    test("links in text, punctuation after them stays") {
        c("Дивись: https://example.org/a?utm_source=x. Гарно!") shouldBe "Дивись: https://example.org/a. Гарно!"
        c("(https://example.org/?fbclid=1)") shouldBe "(https://example.org/)"
        c("https://en.wikipedia.org/wiki/Kyiv_(city)") shouldBe "https://en.wikipedia.org/wiki/Kyiv_(city)"
    }
    test("nothing to clean: the same text") {
        val t = "https://example.org/a?q=1&b=2 and text"
        (c(t) === t || c(t) == t) shouldBe true
        c("no links here") shouldBe "no links here"
    }
})
