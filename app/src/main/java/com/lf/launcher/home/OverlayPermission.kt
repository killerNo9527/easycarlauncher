package com.lf.launcher.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.DataOutputStream

/**
 * 「显示在其他应用上层」（SYSTEM_ALERT_WINDOW）授权。
 *
 * 车机上这条路特别难走：大部分车机把「其他应用」设置页砍了，ACTION_MANAGE_OVERLAY_PERMISSION
 * 根本没人接。所以按可靠性从高到低排三级，一级不成就降级，最后给用户一条 adb 命令兜底：
 *
 *   1. 已经授权 → 直接返回
 *   2. 有 root  → appops set <pkg> SYSTEM_ALERT_WINDOW allow（车机自带 su 时最省事）
 *   3. 有系统页 → 打开授权页让用户点一下
 *   4. 都没有   → 把 adb 命令显示出来
 *
 * 注意 SYSTEM_ALERT_WINDOW 是 appop 不是运行时权限，`pm grant` 对它无效，只能 appops / 设置页。
 */
object OverlayPermission {

    private const val TAG = "OverlayPerm"

    /** 授权结果 */
    enum class Result {
        /** 本来就有 */
        ALREADY_GRANTED,

        /** 刚用 root 授上了 */
        GRANTED_BY_ROOT,

        /** 已经打开系统授权页，等用户点 */
        OPENED_SETTINGS,

        /** 两条路都不通，只能手动敲 adb */
        NEED_ADB,
    }

    fun isGranted(context: Context, pkg: String = context.packageName): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || runCatching {
            if (pkg == context.packageName) Settings.canDrawOverlays(context)
            else appopAllowed(context, pkg)
        }.getOrDefault(false)

    /**
     * 走一遍授权流程。
     * @param autoRoot 有 root 时是否直接授权（不给用户看设置页）
     */
    fun ensure(context: Context, pkg: String = context.packageName, autoRoot: Boolean = true): Result {
        val ctx = context.applicationContext
        if (isGranted(ctx, pkg)) return Result.ALREADY_GRANTED
        if (autoRoot && grantViaRoot(ctx, pkg)) return Result.GRANTED_BY_ROOT
        if (pkg == ctx.packageName && openSettings(ctx)) return Result.OPENED_SETTINGS
        return Result.NEED_ADB
    }

    /** 打开系统的「显示在其他应用上层」页；车机没有这个页面时返回 false */
    fun openSettings(context: Context): Boolean {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "没有悬浮窗授权页: $it") }
            .isSuccess
    }

    /**
     * root 下用 appops 授权。一条 su 会话里 set 完顺手 get 一次，省得再弹一次授权框。
     * 没有 su 文件直接返回 false，不弹窗、不等待。
     */
    fun grantViaRoot(context: Context, pkg: String): Boolean {
        if (!hasRootBinary()) return false
        val out = runCatching {
            val p = Runtime.getRuntime().exec("su")
            DataOutputStream(p.outputStream).use {
                it.writeBytes(
                    "appops set $pkg SYSTEM_ALERT_WINDOW allow\n" +
                        "appops get $pkg SYSTEM_ALERT_WINDOW\n" +
                        "exit\n"
                )
            }
            val text = p.inputStream.bufferedReader().readText()
            p.waitFor()
            text
        }.getOrDefault("")
        val ok = out.contains("allow")
        Log.i(TAG, "root appops $pkg -> $ok, out=$out")
        return ok
    }

    /**
     * 读别人的 appops 实际值（Settings.canDrawOverlays 只认自己）。
     * appops 要 shell 权限，所以只能借 su；没 su 就老实返回 false。
     */
    fun appopAllowed(context: Context, pkg: String): Boolean {
        if (!hasRootBinary()) return false
        return runCatching {
            val p = Runtime.getRuntime().exec("su")
            DataOutputStream(p.outputStream).use {
                it.writeBytes("appops get $pkg SYSTEM_ALERT_WINDOW\nexit\n")
            }
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out.contains("allow")
        }.getOrDefault(false)
    }

    /**
     * 只看有没有 su 文件，**不执行**它。
     * 执行 su 会弹 SuperSU 授权框，在设置页里刷新一次列表就弹一次，体验很差。
     */
    fun hasRootBinary(): Boolean = ROOT_PATHS.any { java.io.File(it).exists() }

    private val ROOT_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su",
        "/su/bin/su", "/system/sbin/su", "/vendor/bin/su", "/data/local/su",
    )

    /** 给用户抄的命令 */
    fun adbCommand(pkg: String = "com.lf.launcher"): String =
        "adb shell appops set $pkg SYSTEM_ALERT_WINDOW allow"

    /** 设置页上显示的一句话状态。这里不能执行 su，只能看文件 */
    fun describe(context: Context): String = when {
        isGranted(context) -> "已授权"
        hasRootBinary() -> "未授权（点一下用 root 授权）"
        else -> "未授权（需 adb 授权）"
    }
}
