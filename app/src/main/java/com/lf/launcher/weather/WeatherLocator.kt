package com.lf.launcher.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.lf.launcher.settings.Keys
import com.lf.launcher.settings.SettingsStore

/**
 * 天气卡的城市自动定位。
 *
 * ## 只做「拿坐标」，不做「查城市」
 *
 * 逆地理编码（坐标 → 城市名）要天气服务商的 key，而 key 只在服务端（见 [WeatherApi] 的注释），
 * 所以这里拿到 `(lat, lon)` 就交给代理，由服务端去调和风的城市搜索接口反查城市名。
 * 好处是 App 侧永远不需要知道 key，朋友装同一个包也一样能用。
 *
 * ## 三个现实约束
 *
 * 1. **车机的 GPS 冷启动很慢**（地库出来要几十秒）。所以先看 `getLastKnownLocation`：
 *    30 分钟内的坐标直接拿来用，超出才真去等一次定位；
 * 2. **定位权限是危险权限**，只在用户打开「自动定位城市」时才申请，拒绝就回退成手填城市；
 * 3. **车会跑**，坐标不能只取一次就不管 —— 每次刷新天气前判断新鲜度，过时了才重新定位。
 */
object WeatherLocator {

    /** 拿不到精确定位时退而求其次：城市级足够定位到「哪个市」 */
    val PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    /** 坐标多久算过时。车 30 分钟能开出去一百多公里，再长就可能跨市了 */
    private const val FRESH_MS = 30 * 60 * 1000L

    /** 单次定位最多等多久。等不到就用旧坐标（或手填城市），不能让卡片一直转圈 */
    private const val TIMEOUT_MS = 12_000L

    /** 默认关：定位是危险权限，不该在用户没表过态的时候弹框 */
    fun enabled(): Boolean = SettingsStore.getBool(Keys.WEATHER_AUTO_CITY, false)

    fun setEnabled(on: Boolean) = SettingsStore.setBool(Keys.WEATHER_AUTO_CITY, on)

    fun hasPermission(ctx: Context): Boolean = PERMISSIONS.any {
        ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    /** 上次定位的坐标；过时了也返回，供「先用旧的顶上」用 */
    fun cachedLocation(): Triple<Double, Double, Long>? {
        val lat = SettingsStore.getString(Keys.WEATHER_LAT, "").toDoubleOrNull() ?: return null
        val lon = SettingsStore.getString(Keys.WEATHER_LON, "").toDoubleOrNull() ?: return null
        val at = SettingsStore.getString(Keys.WEATHER_LOC_AT, "").toLongOrNull() ?: 0L
        return Triple(lat, lon, at)
    }

    fun isFresh(): Boolean {
        val c = cachedLocation() ?: return false
        return System.currentTimeMillis() - c.third < FRESH_MS
    }

    /**
     * 拿一次坐标。缓存新鲜就直接回，否则真去定位一次（最多等 [TIMEOUT_MS]）。
     *
     * 回调一定在主线程，且**只会被调一次**，拿不到就是 null。
     */
    fun resolve(ctx: Context, cb: (Triple<Double, Double, Long>?) -> Unit) {
        if (!enabled()) {
            cb(null)
            return
        }
        if (!hasPermission(ctx)) {
            cb(null)
            return
        }
        cachedLocation()?.let {
            if (System.currentTimeMillis() - it.third < FRESH_MS) {
                cb(it)
                return
            }
        }
        request(ctx, cb)
    }

    /** 强制重新定位一次（用户点「重新定位」时用），忽略缓存 */
    fun request(ctx: Context, cb: (Triple<Double, Double, Long>?) -> Unit) {
        if (!hasPermission(ctx)) {
            cb(null)
            return
        }
        val lm = ctx.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            cb(null)
            return
        }
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        ).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

        val session = Single(lm) { loc ->
            if (loc == null) {
                cb(null)
            } else {
                save(loc)
                cb(Triple(loc.latitude, loc.longitude, System.currentTimeMillis()))
            }
        }
        if (providers.isEmpty()) {
            // 定位开关全关着（车机常见：用户把车机定位关了）——旧坐标比 null 有用
            session.immediate()
            return
        }
        session.start(providers)
    }

    private fun save(loc: Location) {
        // 和风只吃两位小数，这里统一截断，省得请求体里一串小数
        SettingsStore.setString(Keys.WEATHER_LAT, String.format(java.util.Locale.US, "%.2f", loc.latitude))
        SettingsStore.setString(Keys.WEATHER_LON, String.format(java.util.Locale.US, "%.2f", loc.longitude))
        SettingsStore.setString(Keys.WEATHER_LOC_AT, System.currentTimeMillis().toString())
    }

    /**
     * 一次性的定位会话。
     *
     * 写成类而不是闭包，是因为 [LocationListener] 的回调里要调 `finish`，
     * 而 Kotlin 的局部函数不能前向引用 —— 用字段就绕开了这个限制。
     */
    private class Single(
        private val lm: LocationManager,
        private val cb: (Location?) -> Unit,
    ) : LocationListener {

        private val main = Handler(Looper.getMainLooper())
        private var done = false

        fun start(providers: List<String>) {
            // 有现成的就先用：地库里 GPS 不会出点，但上次开出去时留下的坐标是对的
            bestKnown()?.let {
                if (System.currentTimeMillis() - it.time < 10 * 60 * 1000L) {
                    finish(it)
                    return
                }
            }
            providers.forEach { p ->
                runCatching { lm.requestLocationUpdates(p, 0L, 0f, this, Looper.getMainLooper()) }
            }
            main.postDelayed({ finish(bestKnown()) }, TIMEOUT_MS)
        }

        /** 一个 provider 都开不起来时：只能用历史坐标，没有就算了 */
        fun immediate() = finish(bestKnown())

        private fun bestKnown(): Location? = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).mapNotNull { p ->
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()
        }.maxByOrNull { it.time }

        override fun onLocationChanged(location: Location) = finish(location)

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit

        private fun finish(loc: Location?) {
            if (done) return
            done = true
            main.removeCallbacksAndMessages(null)
            runCatching { lm.removeUpdates(this) }
            cb(loc)
        }
    }
}
