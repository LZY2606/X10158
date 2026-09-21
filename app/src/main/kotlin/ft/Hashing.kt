package ft

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Public, stable algorithms used by the tracker.
 *
 * Bucketing:
 *   input = flagKey + "." + salt + "." + stableIdentity
 *   bucket = (first 8 hex chars of SHA-256(input) parsed as big-endian uint32) % 10000
 *
 * The bucket input deliberately contains NO rule index, rule id or version:
 * reordering rules while leaving a rollout clause (same salt) unchanged keeps
 * the same identity in the same slice.
 *
 * Sensitive summary:
 *   key = SHA-256("summary-v1|" + project.domainSecret)
 *   tag = first 16 hex chars of HMAC-SHA256(key, field + "=" + jsonCanonical(value))
 * Same field + same value repeats the same tag within a project; tags in a
 * project with a different domain secret cannot be correlated.
 */
object Hashing {
    const val BP_TOTAL = 10_000

    fun bucketInput(flagKey: String, salt: String, stableIdentity: String): String =
        "$flagKey.$salt.$stableIdentity"

    fun bucket(flagKey: String, salt: String, stableIdentity: String): Int {
        val digest = sha256Hex(bucketInput(flagKey, salt, stableIdentity))
        val top32 = digest.substring(0, 8).toLong(16)
        return (top32 % BP_TOTAL).toInt()
    }

    fun summaryTag(domainSecret: String, field: String, value: JsonValue): String {
        val key = MessageDigest.getInstance("SHA-256")
            .digest(("summary-v1|$domainSecret").toByteArray(Charsets.UTF_8))
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val payload = "$field=${canonical(value)}"
        val out = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        return out.joinToString("") { "%02x".format(it) }.substring(0, 16)
    }

    fun sha256Hex(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    /** Canonical encoding for hashing: object keys sorted, no whitespace. */
    fun canonical(v: JsonValue): String = when (v) {
        is JObj -> v.entries
            .sortedBy { it.first }
            .joinToString(",", "{", "}") { quote(it.first) + ":" + canonical(it.second) }
        is JArr -> v.items.joinToString(",", "[", "]") { canonical(it) }
        is JStr -> quote(v.value)
        is JNum -> Json.n(if (v.isIntegral) v.num.toLong() else v.num).raw
        is JBool -> v.value.toString()
        JNull -> "null"
    }

    private fun quote(s: String): String = Json.write(JStr(s), indent = false)
}
