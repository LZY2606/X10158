package fftrace

import kotlinx.serialization.json.JsonElement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class Digester(projectSecret: String) {
    private val key = SecretKeySpec(projectSecret.toByteArray(Charsets.UTF_8), "HmacSHA256")

    fun digest(field: String, value: JsonElement): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        val bytes = mac.doFinal("fftrace-sensitive-v1|$field|${value}".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(24)
    }
}
