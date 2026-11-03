package com.lf.launcher.settings

import android.content.Context
import android.content.SharedPreferences

/** 桌面配置的读写。M0/M1 用 SharedPreferences，够用且零依赖。 */
object SettingsStore {

    private const val PREF_NAME = "launcher_settings"
    private var sp: SharedPreferences? = null

    fun init(context: Context) {
        if (sp == null) {
            val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            sp = prefs
            // map_mode 已废弃（地图只剩广播这一条路），把旧值清掉，避免以后误读
            if (prefs.contains(Keys.MAP_MODE)) {
                prefs.edit().remove(Keys.MAP_MODE).apply()
            }
        }
    }

    fun getBool(key: String, def: Boolean): Boolean = sp?.getBoolean(key, def) ?: def

    fun setBool(key: String, value: Boolean) {
        sp?.edit()?.putBoolean(key, value)?.apply()
    }

    fun getString(key: String, def: String): String = sp?.getString(key, def) ?: def

    fun setString(key: String, value: String) {
        sp?.edit()?.putString(key, value)?.apply()
    }

    fun getInt(key: String, def: Int): Int = sp?.getInt(key, def) ?: def

    fun setInt(key: String, value: Int) {
        sp?.edit()?.putInt(key, value)?.apply()
    }
}

/** 所有配置项的 key 与默认值集中在这里，避免各处硬编码字符串。 */
object Keys {
    // 桌面：开机自启与「返回键回首页」都没接线，0.9.5 起不再提供（键也删了）
    // 外观
    const val THEME = "theme"
    const val DAY_NIGHT = "day_night"
    const val SHOW_STATUS_BAR = "show_status_bar"
    const val SHOW_NAV_BAR = "show_nav_bar"

    // 状态栏左上角的快捷应用
    const val STATUS_SHORTCUTS = "status_shortcuts"          // 总开关
    const val STATUS_SHORTCUT_APPS = "status_shortcut_apps"  // 包名，逗号分隔，顺序即显示顺序

    // 系统栏 / 安全区
    const val USE_SYSTEM_BARS = "use_system_bars"   // 用系统自带状态栏与导航栏（不沉浸）
    const val PAD_TOP = "pad_top"                   // 全局四边留白（dp），在系统栏占位之外再加
    const val PAD_BOTTOM = "pad_bottom"
    const val PAD_START = "pad_start"
    const val PAD_END = "pad_end"

    // 卡片
    const val CARD_TIME = "card_time"
    const val CARD_MUSIC = "card_music"
    const val CARD_MONITOR = "card_monitor"
    const val CARD_WEATHER = "card_weather"
    const val CARD_ORDER = "card_order"                   // 右栏卡片从上到下的顺序

    // 天气：只走和风直连（Key / JWT 凭据都存本机，没有中间服务）
    const val WEATHER_QW_KEY = "weather_qw_key"           // 和风 key（直连 + API KEY 认证）
    const val WEATHER_QW_HOST = "weather_qw_host"         // 和风 API Host（留空 = devapi.qweather.com）
    const val WEATHER_AUTH = "weather_auth"               // "key" | "jwt"，和风的两种认证方式
    const val WEATHER_JWT_PROJECT = "weather_jwt_project" // JWT: Project ID
    const val WEATHER_JWT_KEY_ID = "weather_jwt_key_id"   // JWT: Key ID（签进 header 的 kid）
    const val WEATHER_JWT_SEED = "weather_jwt_seed"       // JWT: 私钥种子（32 字节，Base64 存）
    const val WEATHER_CITY = "weather_city"               // 要看的城市（自动定位模式下会被定位结果覆盖）
    const val WEATHER_CACHE = "weather_cache"             // 上次成功的数据（断网时先用它撑住）
    const val WEATHER_AUTO_CITY = "weather_auto_city"     // 城市跟随定位（要授权定位权限）
    const val WEATHER_LAT = "weather_lat"                 // 上次定位到的纬度
    const val WEATHER_LON = "weather_lon"                 // 上次定位到的经度
    const val WEATHER_LOC_AT = "weather_loc_at"           // 上次定位的时间戳，用于判断坐标新不新鲜

    // 音乐
    const val MUSIC_COVER = "music_cover"
    const val MUSIC_LYRIC = "music_lyric"

    // 地图：只剩广播控制一条路
    const val MAP_MODE = "map_mode"                       // 已废弃，仅用于清理旧值
    const val MAP_APP = "map_app"                         // 地图应用包名
    const val MAP_AMAP_PLUS_ZERO = "map_amap_plus_zero"   // 坐标是否全传 0
    const val MAP_APP_LABEL = "map_app_label"             // 仅用于设置页显示
    const val MAP_BORDER = "map_border"                   // 是否绘制地图区边框
    const val MAP_BORDER_COLOR = "map_border_color"
    const val AUTO_MAP_ON_OPEN = "auto_map_on_open"       // 打开桌面时自动拉起地图再切回

    // 均衡器（全局，31 段）
    const val EQ_ENABLED = "eq_enabled"                   // 总开关
    const val EQ_PRESET = "eq_preset"                     // flat / car / custom
    const val EQ_GAINS = "eq_gains"                       // 31 个增益，逗号分隔
    const val EQ_PREAMP = "eq_preamp"                     // 前级增益 dB（<=0）
}

object Defaults {
    /** 首页布局：左图右乐（默认）/ 左乐右图 */
    const val THEME_MAP_LEFT = "map_left"
    const val THEME_MAP_RIGHT = "map_right"
    const val THEME = THEME_MAP_LEFT
    const val PAD = 0
    const val DAY_NIGHT = "system"

    const val MAP_BORDER_COLOR = "#4C8DFF"
}
