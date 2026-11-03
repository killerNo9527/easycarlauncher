package com.lf.launcher.weather

import android.os.Handler
import android.os.Looper
import com.lf.launcher.settings.Keys
import com.lf.launcher.settings.SettingsStore
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 天气卡的数据层。
 *
 * 只走**和风直连**：Key / JWT 凭据存在这台车机上，请求直接打到账号自己的 API Host，
 * 中间没有自建服务。早期那套「登录 + 自建代理」已经删掉 —— 多一层服务就多一个可能挂掉的环节，
 * 而它唯一的价值（把 key 藏在服务端）在只有自己和朋友几台车机的场景里并不成立。
 */
data class WeatherNow(
    val city: String,
    val temp: Int,
    val text: String,
    val high: Int? = null,
    val low: Int? = null,
    /** 服务端给出的更新时间（毫秒） */
    val at: Long = 0L,
    /**
     * 区县名（「市中」「薛城」「东城」这种），只在「城市跟随定位」且反查落到区县一级时才有值。
     *
     * 为什么留着它、而不是拼进 [city]：车开到一个区的界内，用户真正想知道的是「我现在在哪个区」，
     * 而「枣庄」这一个词对市中区和薛城区是一样的。所以两者分开存，UI 上「枣庄」当主标、
     * 区名小一号跟在后面 —— 既没丢掉「我在枣庄」这个大前提，又能分清具体在哪个区。
     */
    val district: String = "",
) {
    /** 「枣庄 市中」这种拼成一行给 Toast / 日志用；卡片上是分开两个控件显示的 */
    val displayName: String get() = if (district.isBlank()) city else "$city $district"
}

/** 本地存的凭据与上次数据（都在 SharedPreferences） */
object WeatherStore {

    fun qwKey(): String = SettingsStore.getString(Keys.WEATHER_QW_KEY, "").trim()

    /** 和风 API Host。新版和风每个账号一个专属域名，老账号是 devapi.qweather.com */
    fun qwHost(): String = SettingsStore.getString(Keys.WEATHER_QW_HOST, "").trim()

    fun setQwKey(key: String) = SettingsStore.setString(Keys.WEATHER_QW_KEY, key.trim())

    fun setQwHost(host: String) = SettingsStore.setString(Keys.WEATHER_QW_HOST, host.trim())

    // ---------------------------------------------------------------- 认证方式

    /** 和风的两种认证：老项目的 API KEY，新项目只能 JWT */
    const val AUTH_KEY = "key"
    const val AUTH_JWT = "jwt"

    fun auth(): String {
        val saved = SettingsStore.getString(Keys.WEATHER_AUTH, "")
        return if (saved == AUTH_JWT) AUTH_JWT else AUTH_KEY
    }

    fun setAuth(auth: String) {
        SettingsStore.setString(Keys.WEATHER_AUTH, if (auth == AUTH_JWT) AUTH_JWT else AUTH_KEY)
        QWeatherJwt.invalidate()
    }

    fun jwtProjectId(): String = SettingsStore.getString(Keys.WEATHER_JWT_PROJECT, "").trim()

    fun jwtKeyId(): String = SettingsStore.getString(Keys.WEATHER_JWT_KEY_ID, "").trim()

    /** 私钥种子。存的是 Base64，读出来一定是 32 字节，否则当没配 */
    fun jwtSeed(): ByteArray? {
        val raw = SettingsStore.getString(Keys.WEATHER_JWT_SEED, "")
        if (raw.isEmpty()) return null
        return runCatching { android.util.Base64.decode(raw, android.util.Base64.NO_WRAP) }
            .getOrNull()?.takeIf { it.size == 32 }
    }

    fun saveJwt(projectId: String, keyId: String, seed: ByteArray) {
        SettingsStore.setString(Keys.WEATHER_JWT_PROJECT, projectId.trim())
        SettingsStore.setString(Keys.WEATHER_JWT_KEY_ID, keyId.trim())
        SettingsStore.setString(
            Keys.WEATHER_JWT_SEED,
            android.util.Base64.encodeToString(seed, android.util.Base64.NO_WRAP),
        )
        QWeatherJwt.invalidate()
    }

    fun clearJwt() {
        SettingsStore.setString(Keys.WEATHER_JWT_PROJECT, "")
        SettingsStore.setString(Keys.WEATHER_JWT_KEY_ID, "")
        SettingsStore.setString(Keys.WEATHER_JWT_SEED, "")
        QWeatherJwt.invalidate()
    }

    fun jwtReady(): Boolean = jwtProjectId().isNotEmpty() && jwtKeyId().isNotEmpty() && jwtSeed() != null

    /** 现在「能发请求」吗 */
    fun ready(): Boolean = if (auth() == AUTH_JWT) jwtReady() else qwKey().isNotEmpty()

    fun city(): String = SettingsStore.getString(Keys.WEATHER_CITY, "北京").ifBlank { "北京" }

    fun setCity(city: String) = SettingsStore.setString(Keys.WEATHER_CITY, city.trim())

    /** 上次成功的数据：冷启动 / 断网时先拿它顶上，比显示「--」有用 */
    fun cached(): WeatherNow? = runCatching {
        val raw = SettingsStore.getString(Keys.WEATHER_CACHE, "")
        if (raw.isEmpty()) return null
        val o = JSONObject(raw)
        WeatherNow(
            city = o.optString("city"),
            temp = o.optInt("temp"),
            text = o.optString("text"),
            high = if (o.has("high")) o.optInt("high") else null,
            low = if (o.has("low")) o.optInt("low") else null,
            at = o.optLong("at"),
            district = o.optString("district"),
        )
    }.getOrNull()

    fun saveCache(w: WeatherNow) {
        val o = JSONObject()
            .put("city", w.city).put("temp", w.temp).put("text", w.text)
            .put("at", w.at).put("district", w.district)
        w.high?.let { o.put("high", it) }
        w.low?.let { o.put("low", it) }
        SettingsStore.setString(Keys.WEATHER_CACHE, o.toString())
    }
}

/** 网络调用：取天气。全部在后台线程跑，回调切回主线程。 */
object WeatherApi {

    /** 单线程够了：一次只会有一次刷新在飞 */
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "weather-io").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    fun fetch(city: String, cb: (Result<WeatherNow>) -> Unit) = fetch(city, null, null, cb)

    /**
     * 按坐标取天气。
     *
     * 坐标由 [WeatherLocator] 拿到，坐标 → 城市名（逆地理）由 [QWeatherDirect] 直接问和风。
     */
    fun fetchAt(lat: Double, lon: Double, cb: (Result<WeatherNow>) -> Unit) =
        fetch(null, lat, lon, cb)

    /**
     * 设置页「保存/验证」用：**跟天气卡走同一条路**。
     *
     * 之前这里直接用 `fetch(city)`，于是出现一种很割裂的现象：开了「城市跟随定位」后，
     * 卡片能从坐标拿到数据，而设置页的验证却因为只报城市名而失败（GeoAPI 被应用限制拦掉时尤其明显）。
     * 判断一次定位开关，两边就一致了。
     */
    fun fetchForVerify(context: android.content.Context, cb: (Result<WeatherNow>) -> Unit) {
        if (!WeatherLocator.enabled()) {
            fetch(WeatherStore.city(), cb)
            return
        }
        WeatherLocator.resolve(context) { loc ->
            if (loc == null) fetch(WeatherStore.city(), cb) else fetchAt(loc.first, loc.second, cb)
        }
    }

    private fun fetch(city: String?, lat: Double?, lon: Double?, cb: (Result<WeatherNow>) -> Unit) {
        // 前置检查要按认证方式分开：JWT 模式下 qwKey 本来就是空的
        val missing = if (WeatherStore.auth() == WeatherStore.AUTH_JWT) {
            if (WeatherStore.jwtReady()) null else "JWT 还没配好（Project ID / Key ID / 私钥）"
        } else {
            if (WeatherStore.qwKey().isEmpty()) "还没填和风 Key" else null
        }
        if (missing != null) {
            cb(Result.failure(IllegalStateException(missing)))
            return
        }
        io.execute {
            val result = runCatching { QWeatherDirect.fetchSync(city, lat, lon) }
            main.post { cb(result) }
        }
    }
}
