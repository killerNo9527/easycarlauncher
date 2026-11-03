package com.lf.launcher.ui

import android.app.Activity
import android.view.View
import com.lf.launcher.settings.Defaults
import com.lf.launcher.settings.Keys
import com.lf.launcher.settings.SettingsStore

/**
 * 系统状态栏 / 导航栏策略，以及「给内容让出安全区」的统一入口。
 *
 * ## 两种模式
 *
 * - **独占整屏（默认）**：车机上系统那两条栏会盖住桌面内容，所以默认沉浸式隐藏。
 * - **使用系统栏**：开启后系统状态栏 / 导航栏照常显示，桌面内容自动避让
 *   （系统栏 insets + 用户自定义四边留白）。
 *
 * ## 为什么要自定义留白
 *
 * 车机的系统栏高度经常报不准（有的把空调条算进 statusBar，有的导航栏高度恒为 0），
 * 只靠 insets 会出现「压住一点」或「空一大条」。所以避让量 = `insets + 自定义边距`，
 * 让用户按自己的车机微调。
 *
 * ## 为什么用「布局到栏下面 + 手动补 padding」而不是让系统自动缩窗口
 *
 * 不隐藏系统栏时，窗口默认会被系统缩到两条栏之间 —— 但那样壁纸就铺不满整屏，
 * 状态栏后面会露出系统底色（黑），和桌面风格割裂。这里保持 edge-to-edge
 * （`LAYOUT_FULLSCREEN | LAYOUT_HIDE_NAVIGATION`，只布局、不隐藏），壁纸满屏，
 * 内容用 padding 让位。
 */
object SystemUi {

    /** 当前是否「使用系统自带状态栏与导航栏」 */
    fun useSystemBars(): Boolean = SettingsStore.getBool(Keys.USE_SYSTEM_BARS, false)

    /** 四边自定义留白（dp），顺序：上 / 下 / 左 / 右 */
    fun customPaddingDp(): IntArray = intArrayOf(
        SettingsStore.getInt(Keys.PAD_TOP, Defaults.PAD),
        SettingsStore.getInt(Keys.PAD_BOTTOM, Defaults.PAD),
        SettingsStore.getInt(Keys.PAD_START, Defaults.PAD),
        SettingsStore.getInt(Keys.PAD_END, Defaults.PAD),
    )

    /** 按当前模式设置 systemUiVisibility。onResume / onWindowFocusChanged 都要重来一次 */
    fun apply(activity: Activity) {
        @Suppress("DEPRECATION")
        activity.window.decorView.systemUiVisibility =
            if (useSystemBars()) {
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            } else {
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
    }

    /**
     * 给内容根容器挂上「系统栏 + 自定义留白」的 padding。
     *
     * 只处理一次设置没用：insets 是异步送来的，旋转/分辨率变化还会重发，
     * 所以必须挂监听；[refresh] 用来在偏好在设置页改完之后重新触发一次。
     */
    fun attachContentInset(content: View, withCustomPadding: Boolean = true) {
        @Suppress("DEPRECATION")
        content.setOnApplyWindowInsetsListener { v, insets ->
            val pad = if (!useSystemBars()) {
                intArrayOf(0, 0, 0, 0)
            } else {
                val custom = if (withCustomPadding) customPaddingDp() else intArrayOf(0, 0, 0, 0)
                val d = v.resources.displayMetrics.density
                intArrayOf(
                    insets.systemWindowInsetTop + (custom[0] * d).toInt(),
                    insets.systemWindowInsetBottom + (custom[1] * d).toInt(),
                    insets.systemWindowInsetLeft + (custom[2] * d).toInt(),
                    insets.systemWindowInsetRight + (custom[3] * d).toInt(),
                )
            }
            v.setPadding(pad[2], pad[0], pad[3], pad[1])
            insets
        }
        refresh(content)
    }

    /** 设置改完（或从设置页返回）时重新算一次 padding */
    fun refresh(content: View) {
        content.requestApplyInsets()
    }
}
