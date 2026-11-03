package com.lf.launcher.home

import android.content.Context
import android.content.Intent
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
