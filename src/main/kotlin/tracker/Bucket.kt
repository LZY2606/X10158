package tracker

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 稳定分桶算法（公开）：
 *   input  = "$salt:$flagKey:$identity"
 *   digest = SHA-256(input) 的前 8 字节，按大端解释为无符号整数
 *   bucket = digest % 100        （0..99）
 * 只依赖开关 key、分流 salt 与稳定身份，和规则顺序、规则数量无关。
 */
object Bucket {
    const val BUCKETS = 100

    fun bucketOf(salt: String, flagKey: String, identity: String): Int {
        val input = "$salt:$flagKey:$identity"
        val d = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        var h = 0L
        for (k in 0 until 8) h = (h shl 8) or (d[k].toLong() and 0xff)
        return (h % BUCKETS).toInt()
    }

    /** 按权重（百分比，总和 100）选择变体下标；bucket 落点采用累计区间 [from, to)。 */
    fun choose(bucket: Int, variations: List<Variation>): Int {
        var acc = 0
        variations.forEachIndexed { i, v ->
            acc += v.weight
            if (bucket < acc) return i
        }
        return variations.size - 1
    }
}

/**
 * 敏感字段摘要：HMAC-SHA256，密钥按项目隔离（项目 id + 项目密钥）。
 * 同一项目内同一输入摘要相同（可证明同一输入）；不同项目密钥不同，
 * 摘要不可跨项目关联。轨迹中只出现摘要，不出现原值。
 */
object Digest {
    private fun keyFor(project: Project) =
        SecretKeySpec("flag-tracker/v1:${project.id}:${project.secret}".toByteArray(Charsets.UTF_8), "HmacSHA256")

    fun of(project: Project, attr: String, value: JVal): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(keyFor(project))
        val full = mac.doFinal("$attr=${Json.renderCanonical(value)}".toByteArray(Charsets.UTF_8))
        return full.joinToString("") { "%02x".format(it) }.substring(0, 16)
    }
}
