package ua.pysanka.security

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

class SealedFileTest : FunSpec({
    val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    val otherKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    val chunk = SealedFile.CHUNK
    val label = byteArrayOf(1, 2, 3)

    fun seal(data: ByteArray): ByteArray =
        ByteArrayOutputStream().also { SealedFile.seal(key, label, ByteArrayInputStream(data), it) }.toByteArray()

    for (size in listOf(0, 1, 100, chunk - 1, chunk, chunk + 1, 2 * chunk, 3 * chunk + 77)) {
        test("round trip, $size bytes") {
            val data = Random(size).nextBytes(size)
            SealedFile.readAll(key, label, ByteArrayInputStream(seal(data))) shouldBe data
        }
    }

    for (size in listOf(0, chunk, 2 * chunk + 5)) {
        test("streaming writer, $size bytes, written in small pieces") {
            val data = Random(size + 100).nextBytes(size)
            val out = ByteArrayOutputStream()
            SealedFile.writer(key, label, out).use { w -> data.toList().chunked(1000).forEach { w.write(it.toByteArray()) } }
            SealedFile.readAll(key, label, ByteArrayInputStream(out.toByteArray())) shouldBe data
        }
    }

    test("wrong key fails") {
        val sealed = seal(Random(1).nextBytes(1000))
        shouldThrow<IOException> { SealedFile.readAll(otherKey, label, ByteArrayInputStream(sealed)) }
    }

    test("a changed byte fails") {
        val sealed = seal(Random(2).nextBytes(1000))
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        shouldThrow<IOException> { SealedFile.readAll(key, label, ByteArrayInputStream(sealed)) }
    }

    test("a file cut at a chunk boundary fails") {
        val sealed = seal(Random(3).nextBytes(2 * chunk + 10))
        val cut = sealed.copyOf(12 + chunk + 16)
        shouldThrow<IOException> { SealedFile.readAll(key, label, ByteArrayInputStream(cut)) }
    }

    test("a file under another label (another id) fails") {
        val sealed = seal(Random(5).nextBytes(1000))
        shouldThrow<IOException> { SealedFile.readAll(key, byteArrayOf(9), ByteArrayInputStream(sealed)) }
    }

    test("an extra chunk after the last one fails") {
        val data = Random(6).nextBytes(chunk)
        val sealed = seal(data)
        val extended = sealed + sealed.copyOfRange(12, sealed.size)
        shouldThrow<IOException> { SealedFile.readAll(key, label, ByteArrayInputStream(extended)) }
    }

    test("swapped chunks fail") {
        val sealed = seal(Random(7).nextBytes(3 * chunk))
        val c = chunk + 16
        val swapped = sealed.copyOfRange(0, 12) + sealed.copyOfRange(12 + c, 12 + 2 * c) +
            sealed.copyOfRange(12, 12 + c) + sealed.copyOfRange(12 + 2 * c, sealed.size)
        shouldThrow<IOException> { SealedFile.readAll(key, label, ByteArrayInputStream(swapped)) }
    }

    test("the same data never looks the same twice") {
        val data = Random(4).nextBytes(500)
        (seal(data).contentEquals(seal(data))) shouldBe false
    }
})
