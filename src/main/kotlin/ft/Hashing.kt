package ft

import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Public, stable algorithms used by the tracker:
 *
 *  - [murmur3_32] is the canonical 32-bit MurmurHash3 x86_32 (seed 0) by
 *    Austin Appleby. It is publicly documented and widely reimplemented, so
 *    exported bundles bucket identically in other systems.
 *  - [hmacSha256Hex] is standard HMAC-SHA256 (JCA) used to summarize
 *    sensitive values. Summaries are domain-scoped (project/field) so the same
 *    value cannot be correlated across projects.
 */
object Hashing {
    fun murmur3_32(data: ByteArray, seed: Int = 0): Int {
        val c1 = -889275711 // 0xCC9E2D51
        val c2 = 0x1b873593

        var h1 = seed
        val length = data.size
        val roundedEnd = length and -4

        var i = 0
        while (i < roundedEnd) {
            var k1 = (data[i].toInt() and 0xff) or
                ((data[i + 1].toInt() and 0xff) shl 8) or
                ((data[i + 2].toInt() and 0xff) shl 16) or
                ((data[i + 3].toInt() and 0xff) shl 24)

            k1 *= c1
            k1 = Integer.rotateLeft(k1, 15)
            k1 *= c2

            h1 = h1 xor k1
            h1 = Integer.rotateLeft(h1, 13)
            h1 = h1 * 5 + -430675100 // 0xE6546B64

            i += 4
        }

        var k1 = 0
        when (length and 3) {
            3 -> {
                k1 = (data[roundedEnd + 2].toInt() and 0xff) shl 16
                k1 = k1 or ((data[roundedEnd + 1].toInt() and 0xff) shl 8)
                k1 = k1 or (data[roundedEnd].toInt() and 0xff)
                k1 *= c1
                k1 = Integer.rotateLeft(k1, 15)
                k1 *= c2
                h1 = h1 xor k1
            }
            2 -> {
                k1 = (data[roundedEnd + 1].toInt() and 0xff) shl 8
                k1 = k1 or (data[roundedEnd].toInt() and 0xff)
                k1 *= c1
                k1 = Integer.rotateLeft(k1, 15)
                k1 *= c2
                h1 = h1 xor k1
            }
            1 -> {
                k1 = data[roundedEnd].toInt() and 0xff
                k1 *= c1
                k1 = Integer.rotateLeft(k1, 15)
                k1 *= c2
                h1 = h1 xor k1
            }
        }

        h1 = h1 xor length
        h1 = h1 xor (h1 ushr 16)
        h1 *= -2048144789 // 0x85EBCA6B
        h1 = h1 xor (h1 ushr 13)
        h1 *= -1028477387 // 0xC2B2AE35
        h1 = h1 xor (h1 ushr 16)
        return h1
    }

    fun murmur3_32(text: String): Int = murmur3_32(text.toByteArray(StandardCharsets.UTF_8))

    /** Non-negative bucket in [0, modulus). */
    fun bucket(text: String, modulus: Int = 10000): Int {
        val unsigned = murmur3_32(text).toLong() and 0xffffffffL
        return (unsigned % modulus).toInt()
    }

    fun hmacSha256Hex(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun randomSecret(): String {
        val bytes = ByteArray(24)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
