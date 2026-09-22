package fftracer

/**
 * Stable, public bucketing algorithm: MurmurHash3 x86 32-bit.
 * Bucket input is "flagKey.salt.identity" — rule order never participates,
 * so reordering rules without touching the rollout clause keeps buckets.
 */
object Bucketing {
    fun murmur3x86_32(data: ByteArray, seed: Int = 0): Int {
        var h1 = seed
        val len = data.size
        val nblocks = len / 4
        for (i in 0 until nblocks) {
            val k1 = (data[i * 4].toInt() and 0xff) or
                ((data[i * 4 + 1].toInt() and 0xff) shl 8) or
                ((data[i * 4 + 2].toInt() and 0xff) shl 16) or
                ((data[i * 4 + 3].toInt() and 0xff) shl 24)
            h1 = mix(h1, k1)
        }
        var k1 = 0
        val tail = nblocks * 4
        when (len - tail) {
            3 -> {
                k1 = k1 xor ((data[tail + 2].toInt() and 0xff) shl 16)
                k1 = k1 xor ((data[tail + 1].toInt() and 0xff) shl 8)
                k1 = k1 xor (data[tail].toInt() and 0xff)
                h1 = mix(h1, k1)
            }
            2 -> {
                k1 = k1 xor ((data[tail + 1].toInt() and 0xff) shl 8)
                k1 = k1 xor (data[tail].toInt() and 0xff)
                h1 = mix(h1, k1)
            }
            1 -> {
                k1 = k1 xor (data[tail].toInt() and 0xff)
                h1 = mix(h1, k1)
            }
        }
        h1 = h1 xor len
        h1 = h1 xor (h1 ushr 16)
        h1 *= -0x7a143595
        h1 = h1 xor (h1 ushr 13)
        h1 *= -0x3d4d51cb
        h1 = h1 xor (h1 ushr 16)
        return h1
    }

    private fun mix(h: Int, k0: Int): Int {
        var k1 = k0 * -0x3361d2af
        k1 = Integer.rotateLeft(k1, 15)
        k1 *= -0x7833846b
        var h1 = h xor k1
        h1 = Integer.rotateLeft(h1, 13)
        return h1 * 5 + -0x19e0b4c7
    }

    /** Bucket in [0, 100000). */
    fun bucket(flagKey: String, salt: String, identity: String): Int {
        val hash = murmur3x86_32("$flagKey.$salt.$identity".toByteArray(Charsets.UTF_8))
        return (hash.toLong() and 0xffffffffL).rem(100000L).toInt()
    }

    /** percent in [0,100]; bucket boundary is inclusive of exact endpoints. */
    fun inRollout(flagKey: String, salt: String, identity: String, percent: Double): Boolean {
        val b = bucket(flagKey, salt, identity)
        return b < percent * 1000.0
    }
}
