package fftrace

import java.security.MessageDigest

object Bucketing {
    const val ALGORITHM = "SHA-256(salt:flagKey:identity) -> first8bytes mod 100000"
    const val BUCKET_COUNT = 100_000

    fun hashHex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun bucketOf(salt: String, flagKey: String, identity: String): Pair<Int, String> {
        val input = "$salt:$flagKey:$identity"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        var h = 0L
        for (i in 0 until 8) h = (h shl 8) or (digest[i].toLong() and 0xff)
        val bucket = Math.floorMod(h, BUCKET_COUNT.toLong()).toInt()
        return bucket to digest.joinToString("") { "%02x".format(it) }
    }

    fun threshold(percentage: Double): Int =
        Math.round(percentage * 1000.0).toInt().coerceIn(0, BUCKET_COUNT)
}
