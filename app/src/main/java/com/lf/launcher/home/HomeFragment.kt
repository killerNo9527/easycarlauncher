package com.lf.launcher.home

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintSet
import androidx.fragment.app.Fragment
import com.lf.launcher.R
import com.lf.launcher.LauncherActivity
import com.lf.launcher.map.AmapNavGuard
import com.lf.launcher.map.AmapPlusController
import com.lf.launcher.map.MapMaskOverlay
import com.lf.launcher.lyric.LyricTimeline
import com.lf.launcher.ui.CircleArtView
import com.lf.launcher.ui.ScrollTextView
import com.lf.launcher.music.LastPlayed
import com.lf.launcher.music.MusicController
import com.lf.launcher.settings.CardOrder
import com.lf.launcher.settings.Defaults
import com.lf.launcher.settings.Keys
import com.lf.launcher.settings.SettingsActivity
import com.lf.launcher.settings.SettingsStore
import com.lf.launcher.settings.WeatherJwtDialog
import com.lf.launcher.settings.WeatherKeyDialog
import com.lf.launcher.weather.WeatherApi
import com.lf.launcher.weather.WeatherLocator
import com.lf.launcher.weather.WeatherGlyphView
import com.lf.launcher.weather.WeatherNow
import com.lf.launcher.weather.glyphFor
import com.lf.launcher.weather.WeatherStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 首页：左侧地图区 + 右侧三张卡（时间 / 音乐 / 负载）。
 *
 * 地图区这一版换了实现：不再把地图塞进卡片（那要系统签名），也不再拉起自由窗口，
 * 而是发广播让高德把自己的悬浮窗摆到地图卡的矩形上。
 * 窗口里是我们自己画的 [com.lf.launcher.map.OverlayMapView]，视觉上是卡片的一部分，
 * 物理上是独立窗口 —— 所以平移、切页、退后台都得由本类显式驱动。
 */
class HomeFragment : Fragment(R.layout.fragment_home) {

    private companion object {
        /** 无歌词时的占位态，和「第 N 行」区分开 */
        const val PLACEHOLDER = -100

        /** 正在补拉歌词：留白，不写「暂无歌词」 */
        const val PENDING = -101

        /** 天气自动刷新间隔：10 分钟 */
        const val WEATHER_REFRESH_MS = 10 * 60_000L

        /** 两次请求之间的最小间隔：切来切去不重复打网络 */
        const val WEATHER_MIN_GAP_MS = 5 * 60_000L
    }

    /** 地图卡状态：只剩广播这一条路，所以只有三种 */
    private enum class MapCardState {
        /** 还没选地图应用 */
        NO_APP,

        /** 广播控制就绪：能发 showmap/closemap */
        AMAP_PLUS_READY,

        /** 装了官方包等不认这套广播的目标 */
        AMAP_PLUS_UNSUPPORTED,
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            renderClock()
            renderMonitor()
            handler.postDelayed(this, 5_000L)
        }
    }

    private var timeText: TextView? = null
    private var ampmText: TextView? = null
    private var dateText: TextView? = null
    private var lunarText: TextView? = null
    private var cpuText: TextView? = null
    private var memText: TextView? = null
    private var tempText: TextView? = null

    // 天气卡
    private var weatherCity: TextView? = null
    private var weatherDistrict: TextView? = null
    private var weatherTemp: TextView? = null
    private var weatherText: TextView? = null
    private var weatherRange: TextView? = null
    private var weatherAt: TextView? = null
    private var weatherGlyph: com.lf.launcher.weather.WeatherGlyphView? = null

    /** 有请求在飞时不再发第二个，避免网络慢的时候叠请求把额度刷掉 */
    private var weatherLoading = false

    /** 上次成功刷新的时刻（毫秒），用于「别太勤」 */
    private var weatherFetchedAt = 0L
    private var lastCpu: LongArray? = null

    private var musicTitle: TextView? = null
    private var musicArtist: TextView? = null
    private var albumCover: CircleArtView? = null
    private var btnPlay: ImageView? = null
    private var btnNext: ImageView? = null
    private var btnPrev: ImageView? = null

    /** 卡片内那套控制键，只在底栏隐藏时露出来 */
    private var cardMediaControls: View? = null
    private var btnCardPlay: ImageView? = null
    private var btnCardNext: ImageView? = null
    private var btnCardPrev: ImageView? = null
    private var bottomArt: CircleArtView? = null
    private var bottomTitle: TextView? = null
    private var bottomArtist: TextView? = null
    private var musicProgress: ProgressBar? = null
    private var musicCurrentTime: TextView? = null
    private var musicTotalTime: TextView? = null
    private var lyricLines: List<TextView>? = null
    private var lyricView: View? = null
    /** 当前行（单行滚动文本），播放暂停时它也要停 */
    private var lyricCurrent: ScrollTextView? = null
    /** 当前行下方的括号翻译；没有翻译时 GONE（不占高度） */
    private var lyricTranslation: TextView? = null
    private var lastArtShown: Any? = null
    private var lastArtBottom: Any? = null
    private var lastLineIdx = Int.MIN_VALUE

    /** 上一次渲染用的歌词时间轴；换歌时对象会变，用来作废 [lastLineIdx] 缓存 */
    private var lastTimeline: LyricTimeline? = null

    /** 地图卡上次渲染的「状态指纹」，用来避免在布局过程中反复 setText */
    private var lastFaceKey: String? = null

    private var mapHost: View? = null
    private var mapHintTitle: TextView? = null
    private var mapHintDesc: TextView? = null
    private var mapAction: TextView? = null
    private var mapState: MapCardState = MapCardState.NO_APP

    /** 150ms 刷一次：位置插值 + 当前歌词行切换，够跟手又不至于压垮 MT8666 */
    private val musicTick = object : Runnable {
        override fun run() {
            renderMusic()
            handler.postDelayed(this, 150L)
        }
    }

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.CHINA)
    private val dateFmt = SimpleDateFormat("M月d日", Locale.CHINA)
    private val weekdayFmt = SimpleDateFormat("EEE", Locale.CHINA)

    /** 桌面可见性变化 → 覆盖层 / 高德悬浮窗跟着收放 */
    private val visibilityListener: (Boolean) -> Unit = { visible ->
        if (visible) {
            syncMapCard()
        } else {
            // 高德悬浮窗和它的圆角遮罩都不是我们的 View，页面切走不会自己消失，必须显式收
            AmapPlusController.hide(requireContext().applicationContext)
            MapMaskOverlay.hide()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 主题宿主：后续切换主题时换掉这一层即可
        val host = view.findViewById<FrameLayout>(R.id.home_theme_host)
        layoutInflater.inflate(R.layout.home_theme_classic, host, true)

        timeText = view.findViewById(R.id.time_text)
        ampmText = view.findViewById(R.id.time_ampm)
        dateText = view.findViewById(R.id.date_text)
        lunarText = view.findViewById(R.id.lunar_text)
        cpuText = view.findViewById(R.id.monitor_cpu)
        memText = view.findViewById(R.id.monitor_mem)
        tempText = view.findViewById(R.id.monitor_temp)

        weatherCity = view.findViewById(R.id.weather_city)
        weatherDistrict = view.findViewById(R.id.weather_district)
        weatherTemp = view.findViewById(R.id.weather_temp)
        weatherText = view.findViewById(R.id.weather_text)
        weatherRange = view.findViewById(R.id.weather_range)
        weatherAt = view.findViewById(R.id.weather_at)
        weatherGlyph = view.findViewById(R.id.weather_glyph)
        view.findViewById<View>(R.id.weather_card)?.setOnClickListener { onWeatherClick() }

        musicTitle = view.findViewById(R.id.music_title)
        musicArtist = view.findViewById(R.id.music_artist)
        albumCover = view.findViewById(R.id.album_art)
        musicProgress = view.findViewById(R.id.music_progress)
        musicCurrentTime = view.findViewById(R.id.music_current_time)
        musicTotalTime = view.findViewById(R.id.music_total_time)
        lyricView = view.findViewById<LinearLayout>(R.id.lyric_view)
        buildLyricRows()

        bindMusic(view)

        mapHost = view.findViewById(R.id.map_host)
        mapHintTitle = view.findViewById(R.id.map_hint_title)
        mapHintDesc = view.findViewById(R.id.map_hint_desc)
        mapAction = view.findViewById(R.id.map_action)

        mapHost?.setOnClickListener { onMapCardClick() }
        // 空态按钮自己也要能点：它 clickable，事件不会冒泡到 map_host
        mapAction?.setOnClickListener { onMapCardClick() }
        mapHost?.setOnLongClickListener {
            openMapSettings()
            true
        }

        // 布局一变（状态栏显隐、卡片显隐、分辨率切换）就要重新对齐覆盖层
        mapHost?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncMapCard() }

        LauncherWindowState.addListener(visibilityListener)

        applySettings()
        renderClock()
        renderMonitor()

        mapHost?.post { syncMapCard() }
    }

    // ------------------------------------------------------------------ 天气卡

    /** 每 10 分钟刷一次：天气不差这一会儿，但 service 的免费额度差 */
    private val weatherTick = object : Runnable {
        override fun run() {
            refreshWeather(force = true)
            handler.postDelayed(this, WEATHER_REFRESH_MS)
        }
    }

    private fun onWeatherClick() {
        if (!WeatherStore.ready()) {
            // 没配好就点卡片 = 想用天气，直接把对应的输入框递过去，比让他自己进设置找少三步
            val ctx = context ?: return
            if (WeatherStore.auth() == WeatherStore.AUTH_JWT) {
                WeatherJwtDialog.show(ctx) { refreshWeather(force = true) }
            } else {
                WeatherKeyDialog.show(ctx) { refreshWeather(force = true) }
            }
            return
        }
        refreshWeather(force = true)
    }

    /**
     * 取一次天气。
     *
     * 三个原则：
     * 1. **先渲染缓存再发请求** —— 冷启动 / 地下车库没信号时，卡片上也该有个数；
     * 2. **有请求在飞就不发第二个** —— 网络慢时反复 onResume 会叠请求，额度是有限的；
     * 3. **[WEATHER_MIN_GAP_MS] 内不重复拉** —— 切来切去不该每次都打一次网络。
     */
    private fun refreshWeather(force: Boolean) {
        if (weatherCity == null) return
        if (!SettingsStore.getBool(Keys.CARD_WEATHER, false)) return
        if (!WeatherStore.ready()) {
            renderWeatherLoggedOut()
            return
        }
        if (weatherLoading) return

        // 先用上次的数据顶上，但**不打「离线」**：此刻只是"正在刷新"，
        // 上一次的时间戳本身就能说明数据有多旧。真正的失败在 onFailure 里才标离线。
        WeatherStore.cached()?.let { renderWeather(it, stale = false) }

        val now = System.currentTimeMillis()
        if (!force && now - weatherFetchedAt < WEATHER_MIN_GAP_MS) return

        weatherLoading = true
        weatherText?.text = "获取中…"

        // 自动定位开着就先拿坐标：服务端会用坐标反查城市名，车开去哪天就跟着变。
        // 定位失败（没授权 / 车机定位关了 / 地库里没星）就退回手填城市，不能让卡空着。
        if (WeatherLocator.enabled()) {
            val ctx = context
            if (ctx == null) {
                weatherLoading = false
                return
            }
            WeatherLocator.resolve(ctx) { loc ->
                if (loc != null) {
                    WeatherApi.fetchAt(loc.first, loc.second, ::onWeather)
                } else {
                    fetchByCity()
                }
            }
        } else {
            fetchByCity()
        }
    }

    private fun fetchByCity() {
        WeatherApi.fetch(WeatherStore.city(), ::onWeather)
    }

    private fun onWeather(result: Result<WeatherNow>) {
        weatherLoading = false
        // 回调可能在页面已经销毁后才到，先确认 View 还在
        if (view == null) return
        result.onSuccess { w ->
            weatherFetchedAt = System.currentTimeMillis()
            WeatherStore.saveCache(w)
            // 自动定位时服务端反查到的城市名要记住：下次冷启动、定位还没好的时候，
            // 卡片上显示的仍是这个城市，而不是「北京」这种默认值
            if (WeatherLocator.enabled() && w.city.isNotBlank()) WeatherStore.setCity(w.city)
            renderWeather(w, stale = false)
        }.onFailure { e ->
            if (WeatherStore.cached() == null) {
                renderWeatherError(e.message ?: "获取失败")
            } else {
                weatherAt?.text = "离线"
            }
        }
    }

    /**
     * 城市名分两级：市名当主标，区县名压小一号跟在后面（枣庄 → 枣庄 市中 / 枣庄 薛城）。
     *
     * 区县名可能为空：手填城市名、走进代理模式、或者取数失败都会没有，
     * 这时候把控件整个藏掉，别留个空位把市名挤歪。
     */
    private fun renderCity(city: String?, district: String?) {
        weatherCity?.text = city.orEmpty()
        val d = district.orEmpty().trim()
        weatherDistrict?.text = d
        weatherDistrict?.visibility = if (d.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderWeather(w: WeatherNow, stale: Boolean) {
        renderCity(w.city, w.district)
        weatherTemp?.text = "${w.temp}°"
        weatherText?.text = w.text
        weatherRange?.text = if (w.high != null && w.low != null) {
            "最高 ${w.high}° / 最低 ${w.low}°"
        } else {
            ""
        }
        weatherAt?.text = when {
            stale -> "离线"
            w.at > 0 -> timeFmt.format(Date(w.at))
            else -> ""
        }
        // 图标按编码画；拿不到编码时退化成按中文描述猜（老缓存也有图）
        weatherGlyph?.kind = glyphFor(w.icon, w.text)
    }

    private fun renderWeatherLoggedOut() {
        val jwt = WeatherStore.auth() == WeatherStore.AUTH_JWT
        renderCity(WeatherStore.city(), "")
        weatherTemp?.text = "--"
        weatherText?.text = if (jwt) "点此配 JWT" else "点此填 Key"
        weatherRange?.text = if (jwt) "天气需要先导入 JWT 凭据" else "天气需要先填和风 Key"
        weatherAt?.text = ""
        weatherGlyph?.kind = WeatherGlyphView.Kind.NONE
    }

    private fun renderWeatherError(msg: String) {
        weatherTemp?.text = "--"
        weatherText?.text = "点此重试"
        weatherRange?.text = msg.take(40)
        weatherAt?.text = ""
        // 失败时不画图标：留个太阳在那儿会让人以为数据是好的
        weatherGlyph?.kind = WeatherGlyphView.Kind.NONE
    }

    // ------------------------------------------------------------------ 广播控制高德地图

    private fun hasMapApp(): Boolean = SettingsStore.getString(Keys.MAP_APP, "").isNotEmpty()

    /**
     * 只要选了地图应用就进入 READY 并照常发广播。
     *
     * 这里**不能**用静态探测当门禁：实测官方高德 9.1.0 的接收者是运行时动态注册的，
     * `queryBroadcastReceivers` 永远查不到，但广播真的能送达、也真的会按要求摆窗口
     * （dumpsys 里能看到它自己建了 ty=APPLICATION_OVERLAY、位置正好是卡片矩形的窗口）。
     * 拿静态探测去拦，等于把能用的设备拦成「不认这套广播」。
     */
    private fun resolveState(): MapCardState {
        return if (hasMapApp()) MapCardState.AMAP_PLUS_READY else MapCardState.NO_APP
    }

    /**
     * 地图区唯一的一套逻辑：算状态 → 画卡片空态 → 发广播。
     *
     * 高德悬浮窗是它自己的系统窗口，没有任何跟随能力，所以必须在
     * 布局变化 / 切回首页 / 回到前台 / 设置变更 这几种时机都调一遍。
     */
    private fun syncMapCard() {
        val host = mapHost ?: return
        val ctx = context?.applicationContext ?: return
        mapState = resolveState()
        renderMapCardFace(mapState)

        if (mapState != MapCardState.AMAP_PLUS_READY || !LauncherWindowState.isHomeVisible) {
            AmapPlusController.hide(ctx)
            MapMaskOverlay.hide()
            return
        }
        if (host.width <= 0 || host.height <= 0) return

        val zero = SettingsStore.getBool(Keys.MAP_AMAP_PLUS_ZERO, false)
        val rect = mapRect(host)

        // 遮罩是高德窗口之上的独立窗口，和它同一套几何，开关必须严格同步
        MapMaskOverlay.show(
            requireContext(),
            rect,
            resources.getDimension(R.dimen.home_card_corner_radius),
            listOfNotNull(
                activity?.findViewById<View>(R.id.wallpaper_bg),
                activity?.findViewById<View>(R.id.wallpaper_scrim),
            ),
        )

        val ok = AmapPlusController.show(ctx, rect, zero)
        if (!ok) {
            MapMaskOverlay.hide()
            renderMapCardFace(MapCardState.AMAP_PLUS_UNSUPPORTED)
        }
    }

    /**
     * 让高德把悬浮窗重排一次。
     *
     * 起因：高德**刚进入导航那一帧**会把视口算错——自车不居中、画面右侧被裁、缩放偏大。
     * 手动点一次「全览」等它自己返回就恢复正常，说明它有正确计算的能力，只是那个
     * 转换点没重算。这里在导航刚开始时把窗口抖一下（加宽 3px 再改回），逼它走一遍
     * 布局，效果和「全览→返回」等价，但用户不用手动操作。
     *
     * 不做时也安全：没显示悬浮窗（进全屏导航、桌面不可见）时直接返回。
     */
    private fun nudgeMapWindow() {
        if (!SettingsStore.getBool(Keys.AMAP_NAV_NUDGE, Defaults.AMAP_NAV_NUDGE)) return
        if (!LauncherWindowState.isHomeVisible) return
        val rect = AmapPlusController.currentRect() ?: return
        AmapPlusController.nudge(requireContext().applicationContext, rect)
    }

    private fun mapRect(host: View): Rect {
        val loc = IntArray(2)
        host.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + host.width, loc[1] + host.height)
    }

    private fun borderColor(): Int = runCatching {
        Color.parseColor(SettingsStore.getString(Keys.MAP_BORDER_COLOR, Defaults.MAP_BORDER_COLOR))
    }.getOrDefault(requireContext().getColor(R.color.accent))

    private fun onMapCardClick() {
        when (mapState) {
            MapCardState.NO_APP -> openMapPicker()
            // 悬浮窗盖在卡片上时点不到这里；能点到说明它没出来，兜底进导航。
            // 拉起后由桌面自动切回来——点它的目的是让悬浮窗挂上，不是停在它的全屏界面
            MapCardState.AMAP_PLUS_READY -> launchMapFullscreen()
            // 不认这套广播：直接带去设置，别让用户在空卡片上反复点
            MapCardState.AMAP_PLUS_UNSUPPORTED -> openMapSettings()
        }
    }

    private fun launchMapFullscreen() {
        // 交给 Activity 走统一流程：拉起 → 收尾窗口 → 自动回桌面。
        // 这样点卡片和开机自动拉起用的是同一套状态机，不会互相踩
        val act = activity as? LauncherActivity
        if (act != null) {
            act.launchMapAndComeBack()
            return
        }
        // 理论上到不了（桌面的 Fragment 一定挂在这个 Activity 上），保留原来的兜底
        val ctx = requireContext()
        val pkg = SettingsStore.getString(Keys.MAP_APP, "")
        if (pkg.isEmpty()) {
            openMapPicker()
            return
        }
        val intent = runCatching { ctx.packageManager.getLaunchIntentForPackage(pkg) }.getOrNull()
        if (intent == null) {
            Toast.makeText(ctx, "未找到地图应用，请重新选择", Toast.LENGTH_SHORT).show()
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(ctx, "启动地图失败", Toast.LENGTH_SHORT).show() }
    }

    private fun openMapPicker() {
        startActivity(Intent(requireContext(), com.lf.launcher.settings.MapAppPickerActivity::class.java))
    }

    private fun openMapSettings() {
        startActivity(Intent(requireContext(), SettingsActivity::class.java))
    }

    /** 卡片本体（高德悬浮窗下面的那一层）显示什么 */
    private fun renderMapCardFace(state: MapCardState) {
        val label = MapAppLauncher.selectedLabel(requireContext())
        // syncMapCard 是从 addOnLayoutChangeListener 里调的 —— 在这里无条件 setText 等于
        // 「在布局过程中请求布局」，系统会打 requestLayout() improperly called 警告，
        // 严重时自激成一秒几十次的布局循环（日志缓冲都会被它刷满）。
        // 面子上只有这几种组合，记住上一次的就够了。
        val faceKey = "$state|$label|${AmapPlusController.isShown()}"
        if (faceKey == lastFaceKey) return
        lastFaceKey = faceKey
        when (state) {
            MapCardState.NO_APP -> {
                mapHintTitle?.visibility = View.VISIBLE
                mapHintDesc?.visibility = View.VISIBLE
                mapAction?.visibility = View.VISIBLE
                mapHintTitle?.text = "地图"
                mapHintDesc?.text = "点按选择地图应用"
                mapAction?.text = "选择地图"
            }

            MapCardState.AMAP_PLUS_READY -> {
                // 窗口由高德自己画。「发出去」不等于「画出来了」，所以留一行状态，
                // 否则对方不响应时这块就是一片黑，用户完全不知道发生了什么。
                // 静态探测查不到时补一句提示：动态注册的接收者查不到但能收到。
                val staticSupport = AmapPlusController.probe(requireContext()).supported
                mapHintTitle?.visibility = View.GONE
                mapHintDesc?.visibility = View.VISIBLE
                // 广播只能唤到「已经活着的」高德：实测冷启动前发 showmap，高德进程不会起来，
                // 窗口自然也不会出现。所以这里必须给一句可操作的提示，
                // 而不是让用户对着空卡片猜。
                mapHintDesc?.text = when {
                    !AmapPlusController.isShown() -> ""
                    staticSupport -> "已请求 $label 显示悬浮窗 · 未出现请点按启动"
                    else -> "已请求 $label 显示悬浮窗 · 若未出现，点按这里启动一次"
                }
                mapAction?.visibility = View.GONE
            }

            MapCardState.AMAP_PLUS_UNSUPPORTED -> {
                mapHintTitle?.visibility = View.VISIBLE
                mapHintDesc?.visibility = View.VISIBLE
                mapAction?.visibility = View.VISIBLE
                mapHintTitle?.text = "当前地图不认这套广播"
                mapHintDesc?.text =
                    "没有应用响应 showmap 广播。官方包不支持，" +
                        "只有二次修改版高德才行"
                mapAction?.text = "去设置"
            }
        }

        val host = mapHost ?: return
        host.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = resources.getDimension(R.dimen.home_card_corner_radius)
            setColor(requireContext().getColor(R.color.map_well))
            if (SettingsStore.getBool(Keys.MAP_BORDER, true)) {
                setStroke(dp(1.2f), borderColor())
            }
        }
    }

    override fun onStart() {
        super.onStart()
        syncMapCard()
    }

    override fun onResume() {
        super.onResume()
        applySettings()
        renderMapCardFace(resolveState())
        handler.post(tick)
        handler.post(musicTick)
        refreshWeather(force = false)
        handler.removeCallbacks(weatherTick)
        handler.postDelayed(weatherTick, WEATHER_REFRESH_MS)
        // 从系统授权页返回时没有回调，只能在这里重新读一次权限并挂载
        mapHost?.post { syncMapCard() }
        // 高德一进入导航就把视口算错，这里等着它开口（发 10001）再抖一下窗口
        val ctx = context ?: return
        AmapNavGuard.onNavStart = { nudgeMapWindow() }
        AmapNavGuard.start(ctx)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        handler.removeCallbacks(musicTick)
        handler.removeCallbacks(weatherTick)
        AmapNavGuard.onNavStart = null
        context?.let { AmapNavGuard.stop(it) }
    }

    override fun onDestroyView() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(musicTick)
        LauncherWindowState.removeListener(visibilityListener)
        context?.applicationContext?.let { AmapPlusController.hide(it) }
        MapMaskOverlay.hide()
        MusicController.setListener(null)
        timeText = null
        ampmText = null
        dateText = null
        lunarText = null
        cpuText = null
        memText = null
        tempText = null
        mapHost = null
        mapHintTitle = null
        mapHintDesc = null
        mapAction = null
        musicTitle = null
        musicArtist = null
        albumCover = null
        btnPlay = null
        btnNext = null
        btnPrev = null
        cardMediaControls = null
        btnCardPlay = null
        btnCardNext = null
        btnCardPrev = null
        bottomArt = null
        bottomTitle = null
        bottomArtist = null
        musicProgress = null
        musicCurrentTime = null
        musicTotalTime = null
        lyricLines = null
        lyricView = null
        lyricCurrent = null
        lyricTranslation = null
        lastTimeline = null
        super.onDestroyView()
    }

    // ------------------------------------------------------------------ 音乐

    /**
     * 按 `@integer/lyric_lines` 生成歌词行：紧凑 3 / 标准 5 / 大屏 7。
     *
     * 布局里的 `lyric_view` 只是个空容器 —— 行数跟着屏幕高度走，写死在 XML 里就得同时维护
     * 一张 id 表（上一版就是 5 个固定 id + 按档隐藏」，加一档就得两头改）。
     * 现在一次生成到底：
     *
     * - 行数强制奇数，当前句恒在正中（index = count / 2），切句时视线不用上下追；
     * - 其余行 weight 1、单行省略；当前那一格 weight 1.2 —— 它可能还要叠一行括号翻译；
     * - 当前行用 [ScrollTextView]（放得下居中、放不下横扫），其余行普通 TextView。
     */
    private fun buildLyricRows() {
        val container = lyricView as? LinearLayout ?: return
        val ctx = context ?: return

        val raw = resources.getInteger(R.integer.lyric_lines).coerceIn(1, 9)
        val count = if (raw % 2 == 0) raw + 1 else raw
        val center = count / 2

        container.removeAllViews()
        lyricCurrent = null
        lyricTranslation = null
        val rows = ArrayList<TextView>(count)

        fun lineParams(weight: Float) =
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, weight)

        for (i in 0 until count) {
            if (i == center) {
                val box = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = lineParams(1.2f)
                }
                val text = ScrollTextView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
                    )
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setTextSize(
                        TypedValue.COMPLEX_UNIT_PX,
                        resources.getDimension(R.dimen.lyric_current_text_size),
                    )
                    setTextColor(ctx.getColor(R.color.text_primary))
                    text = ctx.getString(R.string.card_lyric_placeholder)
                }
                val translation = TextView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextSize(
                        TypedValue.COMPLEX_UNIT_PX,
                        resources.getDimension(R.dimen.lyric_translation_text_size),
                    )
                    setTextColor(ctx.getColor(R.color.text_tertiary))
                    visibility = View.GONE
                }
                box.addView(text)
                box.addView(translation)
                container.addView(box)
                lyricCurrent = text
                lyricTranslation = translation
                rows += text
            } else {
                val tv = TextView(ctx).apply {
                    layoutParams = lineParams(1f)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextSize(
                        TypedValue.COMPLEX_UNIT_PX,
                        resources.getDimension(R.dimen.lyric_text_size),
                    )
                    setTextColor(ctx.getColor(R.color.text_secondary))
                    alpha = 0.66f
                }
                container.addView(tv)
                rows += tv
            }
        }

        lyricLines = rows
        // 行换了，上一帧的「第 N 句」缓存必须作废，否则下一帧会因为序号没变直接 return，
        // 留下一片空白歌词
        lastLineIdx = Int.MIN_VALUE
        lastTimeline = null
    }

    private fun bindMusic(root: View) {
        MusicController.start(requireContext())
        MusicController.setListener { renderMusic() }

        // 播放控件都在全局底栏（activity 布局）里，所以从 activity 上找。
        // 音乐卡里只剩封面、歌名、歌词、进度。
        val act = activity
        btnPlay = act?.findViewById(R.id.btn_play)
        btnNext = act?.findViewById(R.id.btn_next)
        btnPrev = act?.findViewById(R.id.btn_prev)
        bottomArt = act?.findViewById(R.id.bottom_art)
        bottomTitle = act?.findViewById(R.id.bottom_title)
        bottomArtist = act?.findViewById(R.id.bottom_artist)

        btnPlay?.setOnClickListener { MusicController.playPause() }
        btnNext?.setOnClickListener { MusicController.next() }
        btnPrev?.setOnClickListener { MusicController.previous() }

        // 卡片内那套：底栏被隐藏时的备用控制键（底栏开着时整行 GONE，点了也看不到）
        cardMediaControls = root.findViewById(R.id.card_media_controls)
        btnCardPlay = root.findViewById(R.id.btn_card_play)
        btnCardNext = root.findViewById(R.id.btn_card_next)
        btnCardPrev = root.findViewById(R.id.btn_card_prev)
        btnCardPlay?.setOnClickListener { MusicController.playPause() }
        btnCardNext?.setOnClickListener { MusicController.next() }
        btnCardPrev?.setOnClickListener { MusicController.previous() }
        applyMediaControlsVisibility()
        bottomTitle?.setOnClickListener { openMusicApp() }
        bottomArtist?.setOnClickListener { openMusicApp() }

        // 点封面或歌名打开播放器，和原版「点击打开音乐」一致
        root.findViewById<View>(R.id.album_cover)?.setOnClickListener { openMusicApp() }
        root.findViewById<View>(R.id.music_title)?.setOnClickListener { openMusicApp() }
    }

    /**
     * 底栏隐藏时，卡片里补一份播放控制键。
     *
     * 那套 prev/play/next 原本只存在于 activity 的全局底栏里，设置里把底栏一关，
     * 桌面上就再没有切歌的入口了。反过来底栏开着时这行必须让位 —— 两套同时出现既重复、
     * 又要跟歌词抢高度。
     *
     * 由 [com.lf.launcher.LauncherActivity.applySettings] 调（那才是开关真正生效的地方），
     * onViewCreated 里也调一次兜底，免得两边时序上错过。
     */
    fun applyMediaControlsVisibility() {
        val navVisible = SettingsStore.getBool(Keys.SHOW_NAV_BAR, true)
        cardMediaControls?.visibility = if (navVisible) View.GONE else View.VISIBLE
    }

    private fun openMusicApp() {
        val intent = runCatching {
            requireContext().packageManager.getLaunchIntentForPackage(MusicController.MD3_PKG)
        }.getOrNull() ?: return
        runCatching { startActivity(intent) }
    }

    private fun renderMusic() {
        if (view == null) return
        val snap = MusicController.snapshot()
        val pos = MusicController.positionMs()

        // 没有活动元数据 = 播放器没连上（开机那几秒最常见）。
        // 这时退回上次播放的那首：一张写着歌名的卡比"暂无播放"有用得多，
        // 点播放键会拉起音乐 App 续播（见 MusicController.playPause 的 controller == null 分支）
        val idle = snap.title.isBlank()
        val lastTitle = if (idle && LastPlayed.has()) LastPlayed.title() else ""
        val lastArtist = if (lastTitle.isNotEmpty()) LastPlayed.artist() else ""
        val cachedArt = if (lastTitle.isNotEmpty()) LastPlayed.art() else null

        val title = snap.title.ifBlank { lastTitle.ifBlank { "暂无播放" } }
        val artistRaw = snap.artist.ifBlank { lastArtist }
        val playing = snap.isPlaying
        val duration = snap.durationMs
        val connected = snap.connected
        val timeline = snap.timeline

        if (musicTitle?.text?.toString() != title) musicTitle?.text = title
        if (musicArtist?.text?.toString() != artistRaw) {
            musicArtist?.text = artistRaw.ifBlank { "点击打开音乐" }
        }

        val art = snap.albumArt ?: cachedArt
        if (art !== lastArtShown) {
            lastArtShown = art
            albumCover?.setArt(art)
        }

        btnPlay?.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        // 卡片内那份要跟底栏保持同一个图标，否则关掉底栏后按下去还是「播放」的样子
        btnCardPlay?.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        // 暂停时把当前行的横向滚动也停住：字还在动会让人以为没暂停
        lyricCurrent?.setScrollPaused(!playing)

        // 底栏那份同步
        val artistText = artistRaw.ifBlank { if (connected) "未在播放" else "未连接播放器" }
        if (bottomTitle?.text?.toString() != title) bottomTitle?.text = title
        if (bottomArtist?.text?.toString() != artistText) bottomArtist?.text = artistText
        if (art !== lastArtBottom) {
            lastArtBottom = art
            bottomArt?.setArt(art)
        }

        if (duration > 0) {
            musicProgress?.max = 1000
            musicProgress?.progress =
                ((pos.toDouble() / duration) * 1000).toInt().coerceIn(0, 1000)
        }
        musicCurrentTime?.text = formatTime(pos)
        musicTotalTime?.text = if (duration > 0) formatTime(duration) else "--:--"

        // 空态且记着上一首：歌词位写一句"点播放继续"，比"未连接播放器"有指向性
        val idleHint = if (idle && lastTitle.isNotEmpty()) "点播放继续" else null
        renderLyric(timeline, connected, pos, snap.lyricPending, idleHint)
    }

    /**
     * 5 行等分，当前行固定落在正中间（index = size/2 = 2）：
     * 前两句 / 当前 / 后两句。这样切句时视线不用上下追。
     */
    private fun renderLyric(
        tl: LyricTimeline?,
        connected: Boolean,
        pos: Long,
        pending: Boolean,
        idleHint: String? = null,
    ) {
        val lines = lyricLines ?: return
        val current = lines.size / 2

        // 换歌会换成一个**全新的** LyricTimeline 实例。此时当前句序号很可能和上一首相同
        // （新歌也是从第 0 句开始），只看 idx 会被下面的「没变化就返回」误判，
        // 结果歌词停在上一首不动 —— 所以对象变了就作废缓存，强制重绘。
        if (tl !== lastTimeline) {
            lastTimeline = tl
            lastLineIdx = Int.MIN_VALUE
        }

        if (tl == null || tl.isEmpty) {
            // 补拉中：换歌瞬间拿到的是「还没带歌词」的元数据，1 秒内会补上。
            // 这时先留白，别闪一下「暂无歌词」—— 那正是用户看到的「卡住暂无歌词」。
            if (pending && connected) {
                if (lastLineIdx != PENDING) {
                    lastLineIdx = PENDING
                    lines.forEach { tv ->
                        tv.text = ""
                        styleLine(tv, false)
                    }
                    showTranslation(null)
                }
                return
            }
            if (lastLineIdx != PLACEHOLDER) {
                lastLineIdx = PLACEHOLDER
                lines.forEachIndexed { i, tv ->
                    styleLine(tv, false)
                    tv.text = when (i) {
                        current -> idleHint ?: if (connected) "暂无歌词" else "未连接播放器"
                        else -> ""
                    }
                }
                showTranslation(null)
            }
            return
        }

        val idx = tl.lineAt(pos)
        if (idx == lastLineIdx) return
        lastLineIdx = idx

        // 这句还能显示多久 = 下一句的起点 - 当前位置。滚动按这个预算安排，
        // 否则长句没扫到尾巴就换句了（「滚动只在能显示的那部分里晃」就是这么来的）
        val nextStart = tl.line(idx + 1)?.startMs
        val budget = if (nextStart != null && nextStart > pos) nextStart - pos else 0L

        for (i in lines.indices) {
            val line = tl.line(idx - current + i)
            val text = line?.text ?: ""
            val tv = lines[i]
            val isCurrent = i == current && text.isNotBlank()
            if (isCurrent) (tv as? ScrollTextView)?.setSweepBudget(budget)
            // 一律单行：当前行的 ScrollTextView 放不下会自己横向滚，
            // 其它行是普通 TextView（style 里 maxLines=1 + ellipsize=end）。
            // 不再折行 —— 折行会把这一格顶满，翻译行被挤掉，五行节奏也乱了
            // 先定字号再设文本：ScrollTextView 在 setText 后会用当前 Paint 量宽度决定滚不滚，
            // 字号晚一步就会拿旧尺寸量（短句被误判成溢出、或者反过来）
            styleLine(tv, isCurrent)
            tv.text = text
            if (isCurrent) showTranslation(line?.translation)
        }
        if (tl.line(idx)?.text.isNullOrBlank()) showTranslation(null)

        animateLineSwap()
    }

    /**
     * 当前行下方紧挨着显示一行括号翻译，形如：
     *
     * ```
     * 二人の間 通り過ぎた風は
     * （从我们之间吹过的风）
     * ```
     *
     * 以前是把翻译拼进当前行的同一段 Spannable 里当第二行，但当前行现在是单行滚动文本，
     * 拼进去会被当成同一句一起滚走 —— 所以拆成独立控件，歌词本体滚它的，翻译钉在下面。
     * 没有翻译就 GONE：GONE 不占高度，歌词本体这一格会自动撑满。
     */
    private fun showTranslation(translation: String?) {
        val tv = lyricTranslation ?: return
        val tr = translation?.trim().orEmpty()
        if (tr.isEmpty()) {
            if (tv.visibility != View.GONE) tv.visibility = View.GONE
            return
        }
        val body = "（$tr）"
        if (tv.text?.toString() != body) tv.text = body
        if (tv.visibility != View.VISIBLE) tv.visibility = View.VISIBLE
    }

    /**
     * 换行过渡：新的一句从下方 10dp 处淡入并上移到位。
     *
     * 5 行是固定等分的，文字是整块替换，没有位移的话会"啪"地跳一下；
     * 给整个歌词块一个 240ms 的上浮 + 淡入，看起来就像歌词向上滚了一行。
     */
    private fun animateLineSwap() {
        val v = lyricView ?: return
        v.animate().cancel()
        v.translationY = dp(10f).toFloat()
        v.alpha = 0.35f
        v.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(240L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun styleLine(tv: TextView, current: Boolean) {
        tv.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            resources.getDimension(
                if (current) R.dimen.lyric_current_text_size else R.dimen.lyric_text_size
            ),
        )
        tv.setTextColor(
            requireContext().getColor(if (current) R.color.text_primary else R.color.text_secondary)
        )
        tv.alpha = if (current) 1f else 0.66f
    }

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    /** 卡片显隐、歌词行数、封面开关——从设置页返回时也会走一次 */
    /**
     * 左图右乐 / 左乐右图：只换左右，比例不动（图 8 : 乐 2 —— 卡片列固定 22%）。
     *
     * 每次都从布局文件重新 clone 一份 ConstraintSet 再改：反方向切换时
     * 上一次加的约束必须清掉，否则会叠出「左边贴着、右边也贴着」的怪状态。
     *
     * 同时统一顶部间距：桌面把自己的状态栏关掉后不该再留一条 status_bar_height 的空带，
     * 顶部间距退到和左右一致的 content_padding_end（整块内容就是一个 16dp 的框）。
     */
    private fun applyHomeFrame() {
        val root = view as? androidx.constraintlayout.widget.ConstraintLayout ?: return
        val cs = ConstraintSet()
        cs.clone(root.context, R.layout.fragment_home)

        val side = resources.getDimensionPixelSize(R.dimen.content_padding_end)
        val gap = resources.getDimensionPixelSize(R.dimen.right_container_card_spacing)
        val top = if (SettingsStore.getBool(Keys.SHOW_STATUS_BAR, true)) {
            resources.getDimensionPixelSize(R.dimen.status_bar_height)
        } else {
            side
        }

        val musicLeft = SettingsStore.getString(Keys.THEME, Defaults.THEME) == Defaults.THEME_MAP_RIGHT
        if (musicLeft) {
            // 卡片列：贴左
            cs.clear(R.id.theme_content_container, ConstraintSet.END)
            cs.connect(
                R.id.theme_content_container, ConstraintSet.START,
                ConstraintSet.PARENT_ID, ConstraintSet.START,
            )
            cs.setMargin(R.id.theme_content_container, ConstraintSet.START, side)
            // 地图：从卡片列右边一直铺到屏幕右边
            cs.connect(
                R.id.map_host, ConstraintSet.START,
                R.id.theme_content_container, ConstraintSet.END,
            )
            cs.connect(R.id.map_host, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            cs.setMargin(R.id.map_host, ConstraintSet.START, gap)
            cs.setMargin(R.id.map_host, ConstraintSet.END, side)
        }

        cs.setMargin(R.id.map_host, ConstraintSet.TOP, top)
        cs.setMargin(R.id.theme_content_container, ConstraintSet.TOP, top)
        cs.applyTo(root)
    }

    private fun applySettings() {
        val v = view ?: return
        // 顺序要先于间距：间距是按「谁是第一张可见卡」算的，重排完再算才对
        applyCardOrder(v)

        v.findViewById<View>(R.id.time_date_card)?.visibility =
            visible(SettingsStore.getBool(Keys.CARD_TIME, true))
        v.findViewById<View>(R.id.music_card)?.visibility =
            visible(SettingsStore.getBool(Keys.CARD_MUSIC, true))
        v.findViewById<View>(R.id.monitor_card)?.visibility =
            visible(SettingsStore.getBool(Keys.CARD_MONITOR, true))
        // 天气卡默认关：没登录之前它只能显示「点此登录」，摆着占位不如不显示
        v.findViewById<View>(R.id.weather_card)?.visibility =
            visible(SettingsStore.getBool(Keys.CARD_WEATHER, false))

        v.findViewById<View>(R.id.album_cover)?.visibility =
            visible(SettingsStore.getBool(Keys.MUSIC_COVER, true))

        val lyricVisible = SettingsStore.getBool(Keys.MUSIC_LYRIC, true)
        v.findViewById<View>(R.id.lyric_view)?.visibility = visible(lyricVisible)

        applyCardSpacing(v)
        applyHomeFrame()

        // 行数按档生成（3 / 5 / 7，见 @integer/lyric_lines）：这里只管整块显隐。
        // 万一档位变了（换屏幕 / 改 density 后 Activity 重建），行数与资源对不上就重建一次。
        if (lyricLines?.size != resources.getInteger(R.integer.lyric_lines)) buildLyricRows()
    }

    /**
     * 按用户配置重排右栏三张卡。
     *
     * 只做「换位置」：`removeAllViews` + 按目标顺序 `addView` 回去 —— 每个 include 出来的
     * 卡自带 `layout_weight` / `topMargin`，LayoutParams 挂在 View 上，摘下来再挂回去不会丢，
     * 所以音乐卡 56 : 负载卡 17 的比例、时间卡固定高都不受影响。
     *
     * 顺序没变时直接返回：这个方法每次 onResume 都会被调，`removeAllViews` 会让整列重画一遍，
     * 在车机上能看到明显一闪。
     */
    private fun applyCardOrder(root: View) {
        val container = root.findViewById<LinearLayout>(R.id.right_container_widgets) ?: return
        val ids = mapOf(
            CardOrder.TIME to R.id.time_date_card,
            CardOrder.MUSIC to R.id.music_card,
            CardOrder.MONITOR to R.id.monitor_card,
            CardOrder.WEATHER to R.id.weather_card,
        )
        val desired = CardOrder.current().mapNotNull { key ->
            ids[key]?.let { root.findViewById<View>(it) }
        }
        // 少了任何一张就不动：宁可保持布局文件里的默认顺序，也不要丢一张卡
        if (desired.size != ids.size || desired.size != container.childCount) return

        var same = true
        for (i in desired.indices) {
            if (container.getChildAt(i) !== desired[i]) {
                same = false
                break
            }
        }
        if (same) return

        container.removeAllViews()
        desired.forEach { container.addView(it) }
    }

    /**
     * 右栏卡片间距：**第一张可见卡不要 top 间距**。
     *
     * 时间卡一关，音乐卡就顶到最上面，而它自己带着 8dp 的 topMargin，
     * 于是整列比左边地图卡低了 8dp —— 顶边对不齐就是这么来的。
     * 这里按「谁是第一张可见卡」重新分配：第一张 0，其余保持常态间距。
     */
    private fun applyCardSpacing(root: View) {
        val container = root.findViewById<LinearLayout>(R.id.right_container_widgets) ?: return
        val gap = resources.getDimensionPixelSize(R.dimen.right_container_card_spacing)
        var firstVisible = true
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            val lp = child.layoutParams as? LinearLayout.LayoutParams ?: continue
            if (child.visibility != View.VISIBLE) continue
            val top = if (firstVisible) {
                firstVisible = false
                0
            } else {
                gap
            }
            if (lp.topMargin != top) {
                lp.topMargin = top
                child.layoutParams = lp
            }
        }
    }

    private fun visible(on: Boolean): Int = if (on) View.VISIBLE else View.GONE

    private fun renderClock() {
        val now = Date()
        timeText?.text = timeFmt.format(now)
        dateText?.text = dateFmt.format(now)
        // 右栏收窄后横排放不下「10月30日 星期五」，拆成两行
        lunarText?.text = weekdayFmt.format(now)
        ampmText?.text = ""
    }

    private fun renderMonitor() {
        memText?.text = readMemoryPercent()
        cpuText?.text = readCpuPercent()
        tempText?.text = readThermal()
    }

    private fun readMemoryPercent(): String {
        val ctx: Context = requireContext()
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return "--"
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        if (info.totalMem <= 0L) return "--"
        val used = info.totalMem - info.availMem
        return "${used * 100 / info.totalMem}%"
    }

    /** CPU 占用：/proc/stat 两次采样差分。首次调用只记录基线，返回 "--" */
    private fun readCpuPercent(): String {
        val now = runCatching {
            File("/proc/stat").readLines()
                .firstOrNull { it.startsWith("cpu ") }
                ?.trim()?.split(Regex("\\s+"))
                ?.drop(1)?.map { it.toLong() }
        }.getOrNull() ?: return "--"

        val last = lastCpu
        lastCpu = now.toLongArray()
        if (last == null || last.size != now.size) return "--"

        val totalDelta = now.sum() - last.sum()
        if (totalDelta <= 0L) return "--"
        // 索引 3 = idle，4 = iowait，都算空闲
        val idleDelta = (now.getOrElse(3) { 0L } + now.getOrElse(4) { 0L }) -
            (last.getOrElse(3) { 0L } + last.getOrElse(4) { 0L })
        val busy = ((totalDelta - idleDelta) * 100 / totalDelta).coerceIn(0L, 100L)
        return "$busy%"
    }

    private fun readThermal(): String = runCatching {
        val zones = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
            ?: return@runCatching "--"
        val max = zones.mapNotNull { zone ->
            val raw = File(zone, "temp").let { if (it.canRead()) it.readText().trim() else null }
                ?: return@mapNotNull null
            val v = raw.toFloatOrNull() ?: return@mapNotNull null
            if (v > 1000f) v / 1000f else v
        }.maxOrNull() ?: return@runCatching "--"
        "${max.toInt()}°"
    }.getOrDefault("--")
}
