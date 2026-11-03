package com.lf.launcher.weather

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 局域网一次性导入页：把天气凭据从手机/电脑搬进车机，**一个字符都不用打**。
 *
 * ## 为什么需要它
 *
 * 两种认证的凭据都不适合在车机软键盘上输入：
 * - **API KEY**：32 位无意义字符，复制时少一位、带个空格都很常见；
 * - **JWT**：一整段 Ed25519 私钥 PEM，几十行、带换行和 `+` `/`，输入法还会吃掉换行。
 *
 * 这里换一条路：车机自己起一个只活两分钟的 HTTP 服务，你在手机浏览器里填表提交，
 * 车机收到就直接存好。同一个页面服务两种模式（[Mode]），只有表单字段不同。
 *
 * ## 安全
 *
 * - 地址是 `http://<车机IP>:<端口>/<4 位随机码>`，根路径 404 —— 蹭不到表单；
 * - 只接受**一次**提交，成功后立刻停服务；
 * - 两分钟没人访问自动关闭（accept 的 soTimeout 兜底）；
 * - 全程只在局域网内，不经过任何外部服务器。
 *
 * 局限要说清楚：这是明文 HTTP + 局域网，同一 Wi-Fi 下有抓包能力的人理论上能看到凭据。
 * 导入完就关服务，窗口只有这一次。
 */
object WeatherImportServer {

    /** 导入哪种凭据。表单字段和落盘逻辑都按这个分。 */
    enum class Mode {
        /** 和风 API KEY：key + host（可空），顺带可以填城市 */
        KEY,

        /** 和风 JWT：Project ID + Key ID + 私钥 PEM */
        JWT,
    }

    /** 空闲多久自动退出 */
    private const val IDLE_TIMEOUT_MS = 120_000

    private val main = Handler(Looper.getMainLooper())

    private var serverSocket: ServerSocket? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    /** 当前这次导入的 URL，UI 拿去显示 */
    @Volatile
    var url: String = ""
        private set

    /**
     * 启动，返回给用户看的地址（拿不到局域网 IP 时返回 null）。
     *
     * @param onSaved 在主线程回调：已解析并落盘
     * @param onError 在主线程回调：端口占用之类
     */
    fun start(
        context: Context,
        mode: Mode,
        onSaved: () -> Unit,
        onError: (String) -> Unit,
    ): String? {
        stop()
        val ip = localIp(context) ?: return null

        val socket = try {
            ServerSocket(0).apply { soTimeout = IDLE_TIMEOUT_MS }
        } catch (e: Exception) {
            main.post { onError(e.message ?: "端口开不起来") }
            return null
        }
        serverSocket = socket
        running = true

        // 4 位随机码：够长到猜不到，短到能手打
        val pin = String.format(Locale.US, "%04d", (1000..9999).random())
        url = "http://$ip:${socket.localPort}/$pin"

        worker = thread(name = "weather-import", isDaemon = true) {
            try {
                while (running) {
                    val client = try {
                        socket.accept()
                    } catch (e: SocketTimeoutException) {
                        break // 没人来，收摊
                    } catch (e: Exception) {
                        break
                    }
                    try {
                        handle(client, pin, mode, onSaved)
                    } catch (_: Exception) {
                        // 单个请求失败不影响等下一个人来
                    }
                }
            } finally {
                stop()
            }
        }
        return url
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        worker = null
        url = ""
    }

    // ------------------------------------------------------------------ 请求处理

    private fun handle(socket: Socket, pin: String, mode: Mode, onSaved: () -> Unit) = socket.use { s ->
        s.soTimeout = 8000
        val input = s.getInputStream()

        val headerText = readHeader(input) ?: return@use
        val firstLine = headerText.lineSequence().firstOrNull().orEmpty()
        val method = firstLine.substringBefore(' ')
        val path = firstLine.substringAfter(' ').substringBefore(' ').substringBefore('?').trimStart('/')

        if (path != pin) {
            respond(s, 404, "text/plain; charset=utf-8", "not found")
            return@use
        }

        if (method.equals("GET", true)) {
            respond(s, 200, "text/html; charset=utf-8", formHtml(mode))
            return@use
        }
        if (!method.equals("POST", true)) {
            respond(s, 405, "text/plain; charset=utf-8", "method not allowed")
            return@use
        }

        val length = Regex("(?i)content-length:\\s*(\\d+)").find(headerText)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val params = parseForm(readBody(input, length))

        val result = when (mode) {
            Mode.KEY -> saveKey(params)
            Mode.JWT -> saveJwt(params)
        }

        respond(s, 200, "text/html; charset=utf-8", page(result.first, result.second))
        if (!result.third) return@use

        main.post { onSaved() }
        // 只收一次，收完就关
        stop()
    }

    /** @return 标题 / 正文 / 是否算保存成功 */
    private fun saveKey(p: Map<String, String>): Triple<String, String, Boolean> {
        // 粘贴经常会带上首尾空格或中间的换行，先清掉再看
        val key = p["k"].orEmpty().filterNot { it.isWhitespace() }
        val host = p["h"].orEmpty().trim()
        val city = p["c"].orEmpty().trim()

        if (key.isEmpty()) {
            return Triple("还差东西", "Key 是必填的。返回上一页补一下。", false)
        }

        WeatherStore.setAuth(WeatherStore.AUTH_KEY)
        WeatherStore.setQwKey(key)
        // Host 留空不动 —— 留空的语义是「我只改了 Key」，不是「把 Host 清掉」。
        // 清掉会悄悄退回官方 devapi 域名，自定义域名的账号会莫名其妙 403。
        if (host.isNotEmpty()) WeatherStore.setQwHost(host)
        if (city.isNotEmpty()) WeatherStore.setCity(city)

        // 和风的 Key 是 32 位。长度不对多半是复制不全，但仍然存下来 ——
        // 让车机那边真的请求一次，用服务端返回的错误码说话，比在这里猜准。
        return if (key.length == 32) {
            Triple("已保存", "车机已经收到。回到车机，天气卡会自动验证一次。", true)
        } else {
            Triple(
                "已保存，但可能贴少了",
                "Key 长度是 ${key.length} 位，和风正常是 32 位。多半是复制不全，" +
                    "车机上验证会报「Key 无效」，到时候重新贴一次就行。",
                true,
            )
        }
    }

    private fun saveJwt(p: Map<String, String>): Triple<String, String, Boolean> {
        val project = p["p"].orEmpty().trim()
        val keyId = p["k"].orEmpty().trim()
        val pem = p["s"].orEmpty()
        val host = p["h"].orEmpty().trim()

        if (project.isEmpty() || keyId.isEmpty() || pem.isBlank()) {
            return Triple("还差东西", "Project ID / Key ID / 私钥三项都要填。返回上一页补一下。", false)
        }

        val seed = QWeatherJwt.parseSeed(pem)
        if (seed == null || seed.size != 32) {
            return Triple(
                "私钥没认出来",
                "要的是控制台里那段 -----BEGIN PRIVATE KEY----- 开头的完整内容（或者 32 字节的 Base64）。",
                false,
            )
        }

        WeatherStore.setAuth(WeatherStore.AUTH_JWT)
        WeatherStore.saveJwt(project, keyId, seed)
        // 和 key 表单一样：留空 = 不改，避免把已存的自定义域名清掉
        if (host.isNotEmpty()) WeatherStore.setQwHost(host)
        return Triple("已保存", "车机已经收到，这个页面可以关掉了。回去点一下天气卡验证。", true)
    }

    /** 读到空行为止，返回整个头（含请求行） */
    private fun readHeader(input: java.io.InputStream): String? {
        val out = ByteArrayOutputStream()
        var match = 0
        val target = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("UTF-8")
            out.write(b)
            match = if (b.toByte() == target[match]) match + 1 else if (b.toByte() == target[0]) 1 else 0
            if (match == 4) return out.toString("UTF-8")
            if (out.size() > 32 * 1024) return null // 防呆
        }
    }

    private fun readBody(input: java.io.InputStream, length: Int): String {
        if (length <= 0) return ""
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buf, read, length - read)
            if (n <= 0) break
            read += n
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun parseForm(body: String): Map<String, String> = body
        .split('&')
        .mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) null
            else URLDecoder.decode(pair.substring(0, i), "UTF-8") to
                URLDecoder.decode(pair.substring(i + 1), "UTF-8")
        }
        .toMap()

    private fun respond(socket: Socket, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $code OK\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        runCatching {
            socket.getOutputStream().apply {
                write(head.toByteArray(Charsets.US_ASCII))
                write(bytes)
                flush()
            }
        }
    }

    // ------------------------------------------------------------------ 页面

    private fun formHtml(mode: Mode): String = """
<!doctype html>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${if (mode == Mode.KEY) "导入和风 Key" else "导入和风私钥"}</title>
<style>
  :root { color-scheme: dark; }
  body { margin:0; padding:24px 18px 48px; background:#12161c; color:#e8edf4;
         font:16px/1.55 -apple-system,"PingFang SC","Microsoft YaHei",sans-serif; }
  h1 { font-size:20px; margin:0 0 6px; }
  p.lead { color:#8f9aa8; margin:0 0 22px; font-size:14px; }
  label { display:block; margin:0 0 18px; font-size:14px; color:#a9b4c2; }
  input, textarea { display:block; width:100%; box-sizing:border-box; margin-top:8px;
    padding:12px 14px; font-size:15px; color:#e8edf4; background:#1b2129;
    border:1px solid #2b333e; border-radius:10px; font-family:ui-monospace,Menlo,monospace; }
  textarea { min-height:150px; resize:vertical; line-height:1.45; }
  button { width:100%; padding:15px; font-size:16px; font-weight:600; color:#0b1016;
    background:#5ab0ff; border:0; border-radius:10px; margin-top:6px; }
  button:active { opacity:.8; }
  .tip { margin-top:22px; font-size:13px; color:#6f7a88; line-height:1.6; }
</style>
${if (mode == Mode.KEY) keyForm() else jwtForm()}
""".trimIndent()

    private fun keyForm(): String = """
<h1>天气 · 和风 API Key</h1>
<p class="lead">填完点下面的按钮，直接把 Key 存进车机。</p>
<form method="post">
  <label>和风 Key<input name="k" required autocomplete="off" spellcheck="false"
    placeholder="32 位字符，控制台「项目管理」里那一列"></label>
  <label>API Host（留空 = 不改）<input name="h" autocomplete="off" spellcheck="false"
    placeholder="新账号必填，形如 abcxyz.re.qweatherapi.com" value="${attr(WeatherStore.qwHost())}"></label>
  <label>城市（留空 = 不改）<input name="c" autocomplete="off" spellcheck="false"
    placeholder="北京" value="${attr(WeatherStore.city())}"></label>
  <button type="submit">保存到车机</button>
</form>
<div class="tip">
  Key 只走局域网、只存这台车机，不经过任何服务器。<br>
  页面对应的地址两分钟后失效，保存成功后也会立刻关闭。
</div>
""".trimIndent()

    /** 填进 HTML 属性里的值要转义，否则带引号的 Host 会把标签截断 */
    private fun attr(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun jwtForm(): String = """
<h1>天气 · 和风 JWT</h1>
<p class="lead">填完点下面的按钮，直接把私钥存进车机。</p>
<form method="post">
  <label>Project ID<input name="p" required autocomplete="off" spellcheck="false"
    placeholder="控制台项目详情里的 Project ID" value="${attr(WeatherStore.jwtProjectId())}"></label>
  <label>Key ID<input name="k" required autocomplete="off" spellcheck="false"
    placeholder="..." value="${attr(WeatherStore.jwtKeyId())}"></label>
  <label>私钥<textarea name="s" required spellcheck="false" placeholder="-----BEGIN PRIVATE KEY-----&#10;MC4CAQAwBQYDK2VwBCIEI...&#10;-----END PRIVATE KEY-----"></textarea></label>
  <label>API Host（新账号必填，形如 abc.re.qweatherapi.com）<input name="h" autocomplete="off" spellcheck="false"
    placeholder="留空 = 不改" value="${attr(WeatherStore.qwHost())}"></label>
  <button type="submit">保存到车机</button>
</form>
<div class="tip">
  私钥只走局域网、只存这台车机，不经过任何服务器。<br>
  页面对应的地址两分钟后失效，保存成功后也会立刻关闭。
</div>
""".trimIndent()

    private fun page(title: String, text: String): String = """
<!doctype html>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>$title</title>
<style>
  body { margin:0; padding:40px 22px; background:#12161c; color:#e8edf4;
         font:16px/1.6 -apple-system,"PingFang SC","Microsoft YaHei",sans-serif; }
  h1 { font-size:20px; margin:0 0 10px; }
  p { color:#a9b4c2; margin:0; font-size:15px; }
</style>
<h1>$title</h1>
<p>$text</p>
""".trimIndent()

    // ------------------------------------------------------------------ 局域网 IP

    /**
     * 车机在局域网里的地址。
     *
     * 优先读 Wi-Fi（车载环境基本都走热点/Wi-Fi），读不到再遍历网卡 —— 有些车机是有线。
     */
    private fun localIp(context: Context): String? {
        runCatching {
            @Suppress("DEPRECATION")
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ip = wifi?.connectionInfo?.ipAddress ?: 0
            if (ip != 0) {
                return String.format(
                    Locale.US,
                    "%d.%d.%d.%d",
                    ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff,
                )
            }
        }
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull()?.hostAddress
        }.getOrNull()
    }
}
