package com.lf.launcher.weather

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * 直连和风：Key / JWT 凭据存在这台车机上，不经过任何中间服务。
 *
 * ```
 * 桌面 App ──(key 或 JWT)──▶ 和风 GeoAPI 反查城市名 → 实时 + 3 天
 * ```
 *
 * 这是现在唯一的数据来源（早期那套自建代理已经删掉）。
 * 代价是凭据落在本机（车机被 root 就能读出来），换来的是少一层中间服务、没有额外延迟。
 *
 * 两个必须记住的地方：
 * 1. **坐标是「经度,纬度」**（`116.41,39.92` 才是北京），写反了会查到海里而且不报错；
 * 2. 新版和风的 GeoAPI 并进了主 host 的 `/geo/v2/...`，老账号的 `geoapi.qweather.com`
 *    是 `/v2/...`（没有 `/geo` 段）。
 */
object QWeatherDirect {

    private const val LEGACY_GEO_HOST = "geoapi.qweather.com"
    private const val DEFAULT_HOST = "devapi.qweather.com"

    /** 阻塞执行，调用方负责放到后台线程（[WeatherApi] 的 io 线程池） */
    fun fetchSync(city: String?, lat: Double?, lon: Double?): WeatherNow {
        val useJwt = WeatherStore.auth() == WeatherStore.AUTH_JWT
        if (useJwt) {
            if (!WeatherStore.jwtReady()) error("JWT 还没配好（Project ID / Key ID / 私钥）")
        } else if (WeatherStore.qwKey().isEmpty()) {
            error("还没填和风 Key")
        }
        val base = baseUrl()

        // 给坐标就反查最近的城市（顺带拿到标准化的城市名），否则按城市名搜
        val where = if (lat != null && lon != null) {
            String.format(Locale.US, "%.2f,%.2f", lon, lat)
        } else {
            city.orEmpty().trim().ifBlank { error("没填城市") }
        }

        // 1) 反查城市：拿标准城市名 + LocationID。
        //    这一步是**锦上添花，不是前提** —— 它经常被账号的「应用限制」整段拦掉
        //    （403 Security Restriction），而天气接口自己就能吃经纬度。所以失败要降级，不能连坐。
        var locationId: String? = null
        var cityName = ""
        var districtName = ""
        var geoError: String? = null
        runCatching {
            val geo = get("$base${geoPath(base)}?location=${enc(where)}&number=1${authQuery()}")
            checkCode(geo, "查城市")
            val loc = geo.optJSONArray("location")?.optJSONObject(0)
                ?: error("找不到城市「$where」")
            val id = loc.optString("id")
            if (id.isBlank()) error("找不到城市「$where」")
            locationId = id
            // 按坐标反查常常落到「区」一级（天安门 → 东城，type 仍写 city、rank 35），
            // 车机卡片上主标写「北京」比「东城」更符合直觉，区名另存一份跟在后面
            // （枣庄的市中区 / 薛城区光看「枣庄」分不出来，所以区名不能丢）。
            // 用户自己填的城市名原样用 —— 别改他的意思。
            val adm2 = loc.optString("adm2")
            val name = loc.optString("name")
            if (lat != null && adm2.isNotBlank() && adm2 != name) {
                cityName = adm2
                districtName = name
            } else {
                cityName = name
            }
        }.onFailure { geoError = it.message }

        // 2) 没有 LocationID 时：
        //    坐标可以直接喂给 /v7/weather/*（实测 location=116.41,39.92 可用）；
        //    中文城市名不行（400 invalid parameter）—— 所以只有城市名又没反查成功就没辙了。
        val location = when {
            locationId != null -> locationId!!
            lat != null && lon != null -> where
            else -> error(geoHint(geoError))
        }

        val now = get("$base/v7/weather/now?location=$location${authQuery()}")
        checkCode(now, "取实时天气")
        val n = now.optJSONObject("now") ?: error("和风没返回实时天气")

        // 高低温是加分项：3 天预报挂了也要能出温度
        var high: Int? = null
        var low: Int? = null
        runCatching {
            val daily = get("$base/v7/weather/3d?location=$location${authQuery()}")
            if (daily.optString("code") == "200") {
                daily.optJSONArray("daily")?.optJSONObject(0)?.let { d ->
                    if (d.has("tempMax")) high = d.optInt("tempMax")
                    if (d.has("tempMin")) low = d.optInt("tempMin")
                }
            }
        }

        // 反查没成功又用了坐标 → 别再拿手填的城市名充数（车可能已经开到别的城市了），
        // 显示「当前位置」比显示一个过时的地名诚实
        val label = cityName.ifBlank {
            if (locationId == null && lat != null) "当前位置" else where
        }

        return WeatherNow(
            city = label,
            temp = n.optString("temp").toIntOrNull() ?: 0,
            text = n.optString("text").ifBlank { "--" },
            high = high,
            low = low,
            at = System.currentTimeMillis(),
            district = districtName,
        )
    }

    /** 反查失败时给一句能照着做的话，而不是把 403 原样抛给用户 */
    private fun geoHint(err: String?): String = when {
        err == null -> "找不到这个城市"
        err.contains("Security Restriction", true) ->
            "和风的城市查询（GeoAPI）被账号的「应用限制」拦了。\n" +
                "两个办法任选：① 到控制台把该凭据的应用限制改成「不限制」；" +
                "② 打开「城市跟随定位」，天气接口能直接吃坐标，不依赖 GeoAPI"
        else -> err
    }

    /** 拼 host：留空用官方默认；允许写 `http://` 开头（本地假服务联调用） */
    private fun baseUrl(): String {
        var host = WeatherStore.qwHost().trim()
        if (host.isEmpty()) host = DEFAULT_HOST
        host = host.trimEnd('/')
        return if (host.startsWith("http://") || host.startsWith("https://")) host else "https://$host"
    }

    private fun geoPath(base: String): String =
        if (base.contains(LEGACY_GEO_HOST)) "/v2/city/lookup" else "/geo/v2/city/lookup"

    /**
     * 认证参数：API KEY 走 query 里的 `&key=`；JWT 什么都不加 —— token 放 Authorization 头。
     *
     * 两者不能混用，和风会直接拒绝，所以这里跟 [WeatherStore.auth] 二选一。
     */
    private fun authQuery(): String =
        if (WeatherStore.auth() == WeatherStore.AUTH_JWT) "" else "&key=${enc(WeatherStore.qwKey())}"

    private fun get(url: String): JSONObject {
        // JWT 每次现签（15 分钟有效），放 Bearer 头；KEY 模式这里是 null
        val bearer = if (WeatherStore.auth() == WeatherStore.AUTH_JWT) {
            QWeatherJwt.current() ?: error("JWT 生成失败：Project ID / Key ID / 私钥有一项不对")
        } else {
            null
        }
        val (code, text) = request("GET", url, bearer, null)
        if (code !in 200..299) {
            val body = runCatching { JSONObject(text) }.getOrNull()

            // 新版控制台（2025 起）的错误体是 {"error":{"status":403,"type":"…#invalid-host",
            // "title":"Invalid Host","detail":"An invalid or unauthorized API Host."}}，
            // 里面**没有 code 字段** —— 老代码只找 code，于是全被显示成「HTTP 403」，
            // 而这条恰恰是最常见的坑：新账号的 Key 只认账号专属域名（xxx.re.qweatherapi.com），
            // 填默认 devapi 一定 403。所以这里必须读懂它。
            body?.optJSONObject("error")?.let { e ->
                val title = e.optString("title")
                val detail = e.optString("detail")
                val type = e.optString("type")
                when {
                    type.contains("invalid-host") || title.contains("Invalid Host", true) ->
                        error(
                            "API Host 不对：这个 Key 只认账号专属域名" +
                                "（形如 abc1234xyz.re.qweatherapi.com，在控制台项目页顶部「API Host」那行），" +
                                "不能留空用默认域名",
                        )
                    title.isNotEmpty() -> error("和风拒绝：$title" + if (detail.isNotEmpty()) "（$detail）" else "")
                }
            }

            // 老格式：HTTP 200/4xx + 体内 code
            val apiCode = body?.optString("code").orEmpty()
            if (apiCode.isNotEmpty() && apiCode != "200") error(message(apiCode))
            error("和风返回 HTTP $code")
        }
        return JSONObject(text.ifBlank { "{}" })
    }

    /**
     * 最小 HTTP 客户端。
     *
     * 不引 OkHttp：整个桌面只有天气这一个外部请求，为它加一个依赖不值当，而且这个工程
     * 一直靠 `--offline` 编译，多一个依赖就多一个下载环节。
     */
    private fun request(
        method: String,
        url: String,
        bearer: String?,
        body: String?,
    ): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "CarLauncher/weather")
            if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            code to text
        } finally {
            conn.disconnect()
        }
    }

    private fun checkCode(o: JSONObject, what: String) {
        val code = o.optString("code")
        if (code == "200") return
        throw IllegalStateException("$what 失败：${message(code)}（$code）")
    }

    private fun message(code: String): String = when (code) {
        "204" -> "请求成功但没有数据"
        "400" -> "参数不对"
        "401" -> "Key 无效，检查有没有复制全"
        "402" -> "超出额度或余额不足"
        "403" -> "没有访问权限，多半是 API Host 填错了"
        "404" -> "查不到这个城市"
        "429" -> "请求太频繁"
        else -> "和风返回异常"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
