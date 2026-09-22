package fftracer

import kotlinx.serialization.json.JsonElement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Sensitive attribute digest: HMAC-SHA256 keyed by the project secret.
 * Same input yields the same digest inside one project (provable equality),
 * but digests cannot be correlated across projects (different keys).
 */
object Digest {
    fun of(projectSecret: String, attribute: String, value: JsonElement): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(projectSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val bytes = mac.doFinal("$attribute=${canonical(value)}".toByteArray(Charsets.UTF_8))
        return "hmac256:" + bytes.joinToString("") { "%02x".format(it) }.take(32)
    }
}
