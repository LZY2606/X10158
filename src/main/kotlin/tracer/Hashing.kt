package tracer

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Hashing {
    private const val BUCKET_SCALE = 0xFFFFFFFFFFFFFFFL // 2^60 - 1, 15 hex digits

    fun sha1Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
        return toHex(digest.digest(input.toByteArray(Charsets.UTF_8)))
    }

    /**
     * Stable, public bucketing algorithm:
     *   bucket = int(sha1("$flagKey.$salt.$identity")[0..15], 16) / (2^60 - 1) * 100
     * The bucket depends only on (flagKey, salt, identity) — never on rule order
     * or rule identity — so reordering rules never silently re-buckets users.
     */
    fun bucketPercent(flagKey: String, salt: String, identity: String): Double {
        val hex = sha1Hex("$flagKey.$salt.$identity")
        val value = hex.substring(0, 15).toLong(16)
        return value.toDouble() / BUCKET_SCALE.toDouble() * 100.0
    }

    /**
     * Sensitive-field summary: HMAC-SHA256 keyed by the per-project secret.
     * Same input inside one project yields the same digest (provable equality),
     * while different projects (different secrets) cannot be correlated.
     */
    fun sensitiveDigest(secret: String, attribute: String, canonicalValue: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return toHex(mac.doFinal("$attribute\n$canonicalValue".toByteArray(Charsets.UTF_8)))
            .substring(0, 32)
    }

    private fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
