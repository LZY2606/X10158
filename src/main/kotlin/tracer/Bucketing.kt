package tracer

/**
 * 稳定分桶：公开算法 MurmurHash3 (x86_32, seed=0)。
 * 输入为 "flagKey:salt:identity"，与规则顺序无关，
 * 因此规则重排而分流条款不变时分桶结果不变。
 */
object Bucketing {

    fun bucket(flagKey: String, salt: String, identity: String): Double {
        val hash = murmur3x86_32("$flagKey:$salt:$identity".toByteArray(Charsets.UTF_8), 0)
        val positive = hash.toLong() and 0xFFFFFFFFL
        return (positive % 100_000L) / 1000.0 // [0, 100)，千分位精度
    }

    fun murmur3x86_32(data: ByteArray, seed: Int): Int {
        val c1 = 0xcc9e2d51.toInt()
        val c2 = 0x1b873593
        var h1 = seed
        val len = data.size
        val roundedEnd = len and 0x7ffffffc.toInt()

        var i = 0
        while (i < roundedEnd) {
            var k1 = (data[i].toInt() and 0xff) or
                ((data[i + 1].toInt() and 0xff) shl 8) or
                ((data[i + 2].toInt() and 0xff) shl 16) or
                ((data[i + 3].toInt() and 0xff) shl 24)
            k1 *= c1
            k1 = k1.rotateLeft(15)
            k1 *= c2
            h1 = h1 xor k1
            h1 = h1.rotateLeft(13)
            h1 = h1 * 5 + 0xe6546b64.toInt()
            i += 4
        }

        var k1 = 0
        val tail = len and 3
        if (tail == 3) k1 = k1 xor ((data[roundedEnd + 2].toInt() and 0xff) shl 16)
        if (tail >= 2) k1 = k1 xor ((data[roundedEnd + 1].toInt() and 0xff) shl 8)
        if (tail >= 1) {
            k1 = k1 xor (data[roundedEnd].toInt() and 0xff)
            k1 *= c1
            k1 = k1.rotateLeft(15)
            k1 *= c2
            h1 = h1 xor k1
        }

        h1 = h1 xor len
        h1 = h1 xor (h1 ushr 16)
        h1 *= 0x85ebca6b.toInt()
        h1 = h1 xor (h1 ushr 13)
        h1 *= 0xc2b2ae35.toInt()
        h1 = h1 xor (h1 ushr 16)
        return h1
    }
}
