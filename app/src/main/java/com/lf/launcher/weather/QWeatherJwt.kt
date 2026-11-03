package com.lf.launcher.weather

import android.util.Base64

/**
 * 和风 JWT（EdDSA / Ed25519）的本地签发。
 *
 * ## 为什么不「输入 JWT」
 *
 * JWT 不是一串固定的东西，它是**每次请求现签**的：
 *
 * ```
 * base64url({"alg":"EdDSA","kid":"<Key ID>"})
 *   + "." + base64url({"sub":"<Project ID>","iat":...,"exp":...})
 *   + "." + base64url(Ed25519 签名)
 * ```
 *
 * 所以真正要保管的是**私钥**（32 字节种子），token 由它派生，15 分钟换一个。
 * 网上那些在线生成 JWT 的工具给的 token 最长 24 小时就过期，贴进 App 第二天就 401。
 *
 * ## 私钥怎么进来
 *
 * 和风控制台给的是一段 PEM（`-----BEGIN PRIVATE KEY-----`）。里面真正的秘密只有 32 字节，
 * 外面那层是 PKCS#8 的壳。所以 [parseSeed] 三种都收：
 * - 整段 PEM（用户直接从控制台复制）
 * - Base64 的 32 字节（导入页可以只传这一小段）
 * - 64 位 hex（有些工具是这么给的）
 */
object QWeatherJwt {

    /** token 有效期。和风允许最长 24 小时，取 15 分钟够用又能对冲车机时钟不准 */
    private const val VALID_SECONDS = 900L

    /** 提前多久算过期（车机时间可能飘） */
    private const val RENEW_BEFORE_MS = 60_000L

    private var cachedToken: String? = null
    private var cachedExpireAt = 0L

    /**
     * 当前可用的 token；配置不全返回 null。
     *
     * 在后台线程调用没问题（[Ed25519] 是纯计算），加锁只是避免并发时签两次。
     */
    @Synchronized
    fun current(): String? {
        val now = System.currentTimeMillis()
        cachedToken?.let { if (now < cachedExpireAt - RENEW_BEFORE_MS) return it }

        val projectId = WeatherStore.jwtProjectId()
        val keyId = WeatherStore.jwtKeyId()
        val seed = WeatherStore.jwtSeed()
        if (projectId.isEmpty() || keyId.isEmpty() || seed == null) return null

        val iat = now / 1000 - 30 // 回拨 30 秒，容忍服务器与本机的时间差
        val exp = iat + VALID_SECONDS
        val header = """{"alg":"EdDSA","kid":"$keyId"}"""
        val payload = """{"sub":"$projectId","iat":$iat,"exp":$exp}"""
        val signingInput = b64(header.toByteArray(Charsets.UTF_8)) + "." + b64(payload.toByteArray(Charsets.UTF_8))
        val signature = Ed25519.sign(seed, signingInput.toByteArray(Charsets.UTF_8))

        val token = "$signingInput.${b64(signature)}"
        cachedToken = token
        cachedExpireAt = exp * 1000
        return token
    }

    /** 配置变了要丢弃旧 token，否则会拿着旧 kid 的签名去请求 */
    @Synchronized
    fun invalidate() {
        cachedToken = null
        cachedExpireAt = 0L
    }

    /**
     * 把用户粘贴的内容解析成 32 字节种子。
     *
     * 接受 PEM / Base64 / hex 三种。解析不了返回 null —— 调用方负责提示。
     */
    fun parseSeed(raw: String): ByteArray? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        // hex：64 个 [0-9a-f]
        val maybeHex = text.replace(Regex("[\\s:]"), "")
        if (maybeHex.length == 64 && maybeHex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            return runCatching {
                ByteArray(32) { i -> maybeHex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            }.getOrNull()
        }

        // PEM / Base64：先把 -----BEGIN XXX----- 这类外壳和所有空白去掉
        val body = text.replace(Regex("-----[^-]*-----"), "").replace(Regex("\\s"), "")
        if (body.isEmpty()) return null
        val bytes = runCatching { Base64.decode(body, Base64.DEFAULT) }.getOrNull() ?: return null

        if (bytes.size == 32) return bytes

        // PKCS#8：… 30 2e 02 01 00 30 05 06 03 2b 65 70 04 22 04 20 <32 字节>
        // 找 04 20 这个 OCTET STRING 头最稳，比算固定偏移可靠
        for (i in 0..bytes.size - 34) {
            if (bytes[i] == 0x04.toByte() && bytes[i + 1] == 0x20.toByte()) {
                return bytes.copyOfRange(i + 2, i + 34)
            }
        }
        // 兜底：PKCS#8 的种子就在最后 32 字节
        if (bytes.size > 32) return bytes.copyOfRange(bytes.size - 32, bytes.size)
        return null
    }

    /** 解析结果对不对，用「能不能算出公钥」间接确认（种子长度对但内容随意也能算，所以只作为格式校验） */
    fun describe(seed: ByteArray?): String = when {
        seed == null -> "未导入"
        seed.size != 32 -> "格式不对"
        else -> "已导入（${seed.size} 字节）"
    }

    private fun b64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
