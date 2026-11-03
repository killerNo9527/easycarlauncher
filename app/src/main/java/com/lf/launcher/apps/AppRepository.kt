package com.lf.launcher.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/**
 * 桌面上的一个可启动应用。
 * M0 只做「查询 + 启动」，M1 再加排序、隐藏、自定义快捷方式。
 */
data class AppEntry(
    val label: String,
    val packageName: String,
    val icon: Drawable?,
    val launchIntent: Intent,
    /**
     * 能否卸载。系统应用（预装、`FLAG_SYSTEM`）在普通权限下删不掉，
     * 长按菜单里就不该出现「卸载」—— 点了弹系统确认框再失败，比没有这一项更糟。
     */
    val canUninstall: Boolean = false,
)

object AppRepository {

    /** 查询所有带 LAUNCHER 入口的应用（排除自己），按名称排序 */
    fun loadLaunchable(context: Context): List<AppEntry> {
        val pm: PackageManager = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        return pm.queryIntentActivities(query, 0)
            .mapNotNull { info ->
                val activity = info.activityInfo ?: return@mapNotNull null
                if (activity.packageName == context.packageName) return@mapNotNull null
                if (!activity.enabled) return@mapNotNull null

                val intent = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(ComponentName(activity.packageName, activity.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

                // 系统预装应用不给「卸载」入口；用户装的和「系统应用的更新」都允许
                val appInfo = activity.applicationInfo
                val flags = appInfo?.flags ?: 0
                val systemOnly = (flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0

                AppEntry(
                    label = runCatching { info.loadLabel(pm)?.toString() }.getOrNull() ?: activity.packageName,
                    packageName = activity.packageName,
                    icon = runCatching { info.loadIcon(pm) }.getOrNull(),
                    launchIntent = intent,
                    canUninstall = !systemOnly,
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label }
    }
}
