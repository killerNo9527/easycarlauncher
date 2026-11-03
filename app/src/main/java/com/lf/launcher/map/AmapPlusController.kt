package com.lf.launcher.map

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.util.Log

/**
 * 广播控制「二次修改版高德」的悬浮窗。
 *
 * 协议（来自修改版应有的实现）：
 * ```
 * 显示：action = com.autonavi.plus.showmap   extras: x / y / w / h
 * 隐藏：action = com.autonavi.plus.closemap
 * ```
 *
 * 注意两件事：
 *
 * 1. **官方包不认这套**。我拆过 com.autonavi.amapauto 9.1.0 的清单，140 条 action 里
 *    没有这两个，dex 里也没有「悬浮窗」字样。所以这里必须**先探测再发**，
 *    否则在官方包上就是空放广播、白等。
 *
 * 2. **坐标能不能生效取决于对方**。Android 没有「A 应用设置 B 应用窗口位置」的 API，
 *    窗口参数只属于创建它的进程。对方愿意读 extras 才有用；不读就只会显示在它自己决定的位置。
 *    所以这里给了一个「全传 0」的开关：当对方只认显示/隐藏、位置由它自己记时用得上。
 */
object AmapPlusController {

    private const val TAG = "AmapPlus"

    const val ACTION_SHOW = "com.autonavi.plus.showmap"
    const val ACTION_HIDE = "com.autonavi.plus.closemap"

    /** 探测结果：哪些包声明了 showmap 的接收者 */
    data class Support(
        val packages: List<String>,
        val supported: Boolean = packages.isNotEmpty(),
        val describe: String = when {
            packages.isEmpty() -> "无静态接收者（可能是运行时动态注册，仍会尝试发送）"
            else -> "支持：" + packages.joinToString(", ")
        },
    )

    private var cached: Support? = null
    private var shown = false
    private var lastRect: Rect? = null
    private var lastZero = false

    /** 探测当前设备上有没有应用监听 showmap。结果缓存，`invalidate()` 可清 */
    fun probe(context: Context): Support {
        cached?.let { return it }
        val i = Intent(ACTION_SHOW)
        val list = runCatching {
            context.packageManager.queryBroadcastReceivers(i, 0)
        }.getOrDefault(emptyList())
        val pkgs = list.mapNotNull { it.activityInfo?.packageName }.distinct()
        val s = Support(pkgs)
        cached = s
        Log.i(TAG, "probe → ${s.describe}")
        return s
    }

    fun invalidate() {
        cached = null
    }

    /**
     * 请求显示悬浮窗。
     *
     * @param rect 卡片在屏幕上的绝对矩形
     * @param zero true = x/y/w/h 全传 0（对方自己决定位置）；false = 传真实坐标
     */
    fun show(context: Context, rect: Rect, zero: Boolean): Boolean {
        // 不做探测定制：静态探测只能看到清单里声明的接收者，实测官方高德 9.1.0 用的是
        // 运行时动态注册，查不到但收得到。所以要发就发，探测结果只用于界面上给提示。
        val support = probe(context)

        // 位置没变就不重复发，避免对方每秒重弹一次
        if (shown && !zero && lastRect == rect) return true

        val intent = Intent(ACTION_SHOW).apply {
            // 精确投递到声明了接收者的那个包：targetSdk 28 时隐式广播也能收，
            // 但显式更稳（对方若提过 targetSdk，静态接收者就收不到隐式广播了）
            support.packages.firstOrNull()?.let { setPackage(it) }
            if (zero) {
                putExtra("x", 0)
                putExtra("y", 0)
                putExtra("w", 0)
                putExtra("h", 0)
            } else {
                // 实测：w/h 是**右下角坐标**，不是宽高。
                // 传 (24,57,1438,843) 得到的窗口是 (24,57)(1414x786) ——
                // 1414 = 1438-24、786 = 843-57，差值正好是 x/y。
                // 所以这里传 right/bottom，窗口才会刚好铺满卡片矩形。
                putExtra("x", rect.left)
                putExtra("y", rect.top)
                putExtra("w", rect.right)
                putExtra("h", rect.bottom)
            }
        }
        runCatching { context.sendBroadcast(intent) }
            .onFailure { Log.w(TAG, "sendBroadcast 失败：$it"); return false }

        shown = true
        lastZero = zero
        lastRect = if (zero) null else Rect(rect)
        Log.i(TAG, "show rect=$rect zero=$zero → ${support.packages.firstOrNull()}")
        return true
    }

    /** 请求隐藏。幂等：没显示过就不发 */
    fun hide(context: Context) {
        if (!shown) return
        val support = probe(context)
        val intent = Intent(ACTION_HIDE).apply {
            support.packages.firstOrNull()?.let { setPackage(it) }
        }
        runCatching { context.sendBroadcast(intent) }
            .onFailure { Log.w(TAG, "hide 失败：$it") }
        shown = false
        lastRect = null
        Log.i(TAG, "hide")
    }

    /** 当前是否处于「已请求显示」状态 */
    fun isShown(): Boolean = shown

    /** 给设置页用的一句话 */
    fun summary(context: Context): String = probe(context).describe
}
