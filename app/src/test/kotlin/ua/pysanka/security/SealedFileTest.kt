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

    fun seal(data: ByteArray): ByteArray =
        ByteArrayOutputStream().also { SealedFile.seal(key, ByteArrayInputStream(data), it) }.toByteArray()

    for (size in listOf(0, 1, 100, chunk - 1, chunk, chunk + 1, 2 * chunk, 3 * chunk + 77)) {
        test("round trip, $size bytes") {
            val data = Random(size).nextBytes(size)
            SealedFile.readAll(key, ByteArrayInputStream(seal(data))) shouldBe data
        }
    }

    test("wrong key fails") {
        val sealed = seal(Random(1).nextBytes(1000))
        shouldThrow<IOException> { SealedFile.readAll(otherKey, ByteArrayInputStream(sealed)) }
    }

    test("a changed byte fails") {
        val sealed = seal(Random(2).nextBytes(1000))
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        shouldThrow<IOException> { SealedFile.readAll(key, ByteArrayInputStream(sealed)) }
    }

    test("a file cut at a chunk boundary fails") {
        val sealed = seal(Random(3).nextBytes(2 * chunk + 10))
        val cut = sealed.copyOf(12 + chunk + 16)
        shouldThrow<IOException> { SealedFile.readAll(key, ByteArrayInputStream(cut)) }
    }

    test("the same data never looks the same twice") {
        val data = Random(4).nextBytes(500)
        (seal(data).contentEquals(seal(data))) shouldBe false
    }
})
