package com.lf.launcher.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import com.lf.launcher.settings.Defaults
import com.lf.launcher.settings.Keys
import com.lf.launcher.settings.SettingsStore

/**
 * 日间 / 夜间模式。
 *
 * ## 资源怎么分
 *
 * 和参考实现（com.zzh.launcher50s.alpha 的 Material3 DayNight）同一套：颜色拆成
 * `values/colors.xml`（日间）与 `values-night/colors.xml`（夜间）两份同名资源，
 * 主题用 `Theme.AppCompat.DayNight.NoActionBar`，由 uiMode 决定取哪一份。
 * 代码里**没有**任何 `if (isNight) 用这个色` 的分支。
 *
 * ## 为什么不用 AppCompatDelegate.setDefaultNightMode
 *
 * 实测在这台 Android 9 上，「固定日间 / 固定夜间」用 `setDefaultNightMode` **不生效**：
 * 偏好写进去了、`getDefaultNightMode()` 也变了，但界面颜色一动不动。
 * 原因是本工程所有 Activity 都在清单里声明了 `uiMode` 的 configChanges（车机上避免
 * 分辨率/转屏把桌面重建掉），AppCompat 判断"这个变化 Activity 自己能处理"，
 * 于是既不重建也不覆盖资源。
 *
 * 所以固定模式改用最直接的办法：在 [ThemedActivity.attachBaseContext] 里把
 * `Configuration.uiMode` 改掉再 `createConfigurationContext`，资源自然按新 uiMode 解析。
 * 「跟随系统」则完全不干预，系统切换时由 [ThemedActivity.onConfigurationChanged] 负责重建。
 */
object Appearance {

    const val FOLLOW = "system"
    const val DAY = "day"
    const val NIGHT = "night"

    fun mode(): String = SettingsStore.getString(Keys.DAY_NIGHT, Defaults.DAY_NIGHT)

    fun isNight(config: Configuration): Boolean =
        (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun isNight(context: Context): Boolean = isNight(context.resources.configuration)

    /**
     * 按当前设置给 Context 套一层 uiMode 覆盖。
     * 跟随系统时原样返回，交给系统决定。
     */
    fun wrap(base: Context): Context {
        val mode = mode()
        if (mode == FOLLOW) return base
        val config = Configuration(base.resources.configuration)
        val mask = Configuration.UI_MODE_NIGHT_MASK
        val night =
            if (mode == NIGHT) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        config.uiMode = (config.uiMode and mask.inv()) or night
        return base.createConfigurationContext(config)
    }

    /**
     * 改模式并立刻让界面生效。
     *
     * 必须自己调 `recreate()`：当前界面是按旧 uiMode inflate 的，
     * 不重建的话颜色不会重新解析。
     */
    fun setMode(activity: Activity, value: String) {
        // 不做「值没变就跳过」的判断：调用方（设置页）是在偏好**已经写入之后**才回调的，
        // 这里再读 mode() 一定等于 value，判断会永远成立、界面永远不重建
        SettingsStore.setString(Keys.DAY_NIGHT, value)
        syncDelegate()
        activity.recreate()
    }

    /**
     * 让 AppCompat 自己也处于目标模式。
     *
     * 必须做这一步：AppCompatActivity 会在 `attachBaseContext` 之后用自己的
     * override configuration 覆盖 Resources，把 [wrap] 设的 uiMode 抹掉
     * （实测日志：wrap 里 night=true，到了 onCreate 变成 false）。
     * 所以最终生效的是 AppCompat 的这套开关，[wrap] 只是兜底。
     */
    fun syncDelegate() {
        val target = when (mode()) {
            DAY -> AppCompatDelegate.MODE_NIGHT_NO
            NIGHT -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != target) {
            AppCompatDelegate.setDefaultNightMode(target)
        }
    }

    /** 设置页展示用 */
    fun describe(context: Context): String = when (mode()) {
        DAY -> "固定日间 · 不跟随系统"
        NIGHT -> "固定夜间 · 不跟随系统"
        else -> "跟随系统 · 当前" + if (isNight(context)) "夜间" else "日间"
    }
}

/**
 * 所有需要跟随日夜切换的 Activity 的基类。
 *
 * 两件事：
 * 1. `attachBaseContext` 里套 uiMode 覆盖（固定日间/夜间）；
 * 2. `uiMode` 真变了就重建一次 —— 清单里声明了 `uiMode` 的 configChanges，系统不会重建我们。
 */
abstract class ThemedActivity : AppCompatActivity() {

    /** 本界面是按哪个模式 inflate 的；用于"在别的页面改了模式"时回来补一次重建 */
    private var appliedMode = ""

    private var lastNight = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Appearance.wrap(newBase))
        appliedMode = Appearance.mode()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastNight = Appearance.isNight(this)
    }

    override fun onResume() {
        super.onResume()
        // 设置页里改了模式：桌面还留在返回栈里没被重建，回到前台补一次
        if (Appearance.mode() != appliedMode) recreate()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val night = Appearance.isNight(newConfig)
        if (night == lastNight) return
        lastNight = night
        // 固定日间/夜间时不跟随系统，配置变化与我们无关
        if (Appearance.mode() == Appearance.FOLLOW) recreate()
    }
}
