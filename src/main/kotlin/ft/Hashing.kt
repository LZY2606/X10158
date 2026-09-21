package ft

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Stable, public bucketing: MurmurHash3 x86 (32-bit), seed 0 — the same
 * algorithm published by Google for flag/experiment bucketing. The bucket
 * input is fully traceable, so identical (flagKey, salt, stableId) tuples
 * always land in the same bucket regardless of rule ordering.
 */
object Murmur3 {
    fun hash32(data: ByteArray, seed: Int = 0): Int {
        val length = data.size
        val nblocks = length / 4
        var h1 = seed
        var i = 0
        repeat(nblocks) {
            val k0 = (data[i].toInt() and 0xff) or
                ((data[i + 1].toInt() and 0xff) shl 8) or
                ((data[i + 2].toInt() and 0xff) shl 16) or
                ((data[i + 3].toInt() and 0xff) shl 24)
            i += 4
            var k = k0
            k = k * -0x33130997
            k = Integer.rotateLeft(k, 15)
            k = k * -0x1a4a739c
            h1 = h1 xor k
            h1 = Integer.rotateLeft(h1, 13)
            h1 = h1 * 5 + -0x19ab949c
        }
        var k1 = 0
        when (length and 3) {
            3 -> {
                k1 = k1 xor ((data[i + 2].toInt() and 0xff) shl 16)
                k1 = k1 xor ((data[i + 1].toInt() and 0xff) shl 8)
                k1 = k1 xor (data[i].toInt() and 0xff)
                k1 = k1 * -0x33130997
                k1 = Integer.rotateLeft(k1, 15)
                k1 = k1 * -0x1a4a739c
                h1 = h1 xor k1
            }
            2 -> {
                k1 = k1 xor ((data[i + 1].toInt() and 0xff) shl 8)
                k1 = k1 xor (data[i].toInt() and 0xff)
                k1 = k1 * -0x33130997
                k1 = Integer.rotateLeft(k1, 15)
                k1 = k1 * -0x1a4a739c
                h1 = h1 xor k1
            }
            1 -> {
                k1 = k1 xor (data[i].toInt() and 0xff)
                k1 = k1 * -0x33130997
                k1 = Integer.rotateLeft(k1, 15)
                k1 = k1 * -0x1a4a739c
                h1 = h1 xor k1
            }
        }
        h1 = h1 xor length
        h1 = fmix32(h1)
        return h1
    }

    fun hash32(text: String, seed: Int = 0): Int =
        hash32(text.toByteArray(Charsets.UTF_8), seed)

    /** Result in 0..9999 (basis points). */
    fun bucket10k(text: String, seed: Int = 0): Int {
        val unsigned = hash32(text, seed).toLong() and 0xffffffffL
        return (unsigned % 10000L).toInt()
    }

    private fun fmix32(hIn: Int): Int {
        var h = hIn
        h = h xor (h ushr 16)
        h = h * -0x7a143588
        h = h xor (h ushr 13)
        h = h * -0x3d4d51cb
        h = h xor (h ushr 16)
        return h
    }
}

object SecretHash {
    fun hmacSha256Hex(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") {
            "%02x".format(it)
        }
    }

    /** Short, unlinkable digest shown in traces for sensitive fields. */
    fun digest(salt: String, value: String): String =
        hmacSha256Hex("ft-sensitive|" + salt, value).substring(0, 16)
}
