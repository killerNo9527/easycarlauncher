package com.lf.launcher.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.lf.launcher.settings.Keys
import com.lf.launcher.settings.SettingsStore

/**
 * 地图应用的存取与「全屏打开导航」。
 *
 * 早先这里还负责两种把地图塞进卡片的通道（虚拟屏内嵌 / 系统自由窗口），
 * 现在统一由 [com.lf.launcher.map.MapOverlayWindow] 的悬浮覆盖层承担，
 * 这个对象只保留两件事：
 *
 * 1. 记住用户选的是哪个地图 App（包名 + 展示名）；
 * 2. 点按地图区 / 覆盖层时，把地图以全屏导航打开（覆盖层只是"背景画面"，
 *    真要导航时进地图自己的界面）。
 */
object MapAppLauncher {

    private const val TAG = "MapAppLauncher"

    /**
     * 「桌面图标」指向的是启动中转页，真正的地图界面是另一个 Activity。
     *
     * 高德的 `getLaunchIntentForPackage` 拿到的是
     * `com.autonavi.auto.remote.fill.UsbFillActivity`（远程填充/启动中转页），
     * 它自己再跳 `com.autonavi.amapauto.MainMapActivity` —— 对桌面来说就是
     * **两次 onPause、两次「被盖住」**，自动流程要来回切两次，看起来像地图在抢前台。
     *
     * 实测 `MainMapActivity` 是 `exported=true`，直接起它只有一次 START，
     * 悬浮窗、日夜联动、导航全都正常。
     */
    private val DIRECT_MAIN = mapOf(
        "com.autonavi.amapauto" to "com.autonavi.amapauto.MainMapActivity",
    )

    fun selectedPackage(): String = SettingsStore.getString(Keys.MAP_APP, "")

    fun selectedLabel(context: Context): String {
        val pkg = selectedPackage()
        if (pkg.isEmpty()) return "未选择"
        val saved = SettingsStore.getString(Keys.MAP_APP_LABEL, "")
        if (saved.isNotEmpty()) return saved
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    /** 打开地图应用（全屏导航） */
    fun launchFullscreen(context: Context): Boolean {
        val ctx = context.applicationContext
        val pkg = selectedPackage()
        if (pkg.isEmpty()) {
            Toast.makeText(ctx, "请先在设置 → 地图里选择地图应用", Toast.LENGTH_SHORT).show()
            return false
        }

        // 先试直接起主界面：少一次中转跳转，就少一轮抢前台
        val direct = DIRECT_MAIN[pkg]
        if (direct != null) {
            val intent = Intent().apply {
                component = ComponentName(pkg, direct)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            val ok = runCatching { ctx.startActivity(intent) }.isSuccess
            if (ok) {
                Log.i(TAG, "直启主界面 $pkg/$direct")
                return true
            }
            Log.w(TAG, "直启主界面失败，回退图标入口")
        }

        val intent = runCatching { ctx.packageManager.getLaunchIntentForPackage(pkg) }.getOrNull()
        if (intent == null) {
            Toast.makeText(ctx, "未找到地图应用，请在设置里重新选择", Toast.LENGTH_SHORT).show()
            return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching { ctx.startActivity(intent) }
            .onFailure { Toast.makeText(ctx, "启动地图失败", Toast.LENGTH_SHORT).show() }
            .isSuccess
    }
}
