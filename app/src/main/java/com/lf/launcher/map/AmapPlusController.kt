package com.lf.launcher.map

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
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

    private val main = Handler(Looper.getMainLooper())
    private var nudgePending: Runnable? = null

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

    /**
     * 抖一下窗口，逼高德重新布局一次。
     *
     * ## 为什么需要
     *
     * 高德在这个悬浮窗里**进入导航的那一刻**会用错视口：自车不居中、画面右侧被裁、
     * 缩放偏大。但**手动点一次「全览」再自动回到导航就恢复正常**——说明它有能力算对，
     * 只是那个转换点没重算。用户从别的桌面（全屏）开始导航再切回来也正常，同样印证
     * 这点：窗口尺寸变过，它就重排了。
     *
     * 实测确认过两件事，所以这个办法可行：
     * 1. 收到 showmap 只是 `updateViewLayout`，**窗口对象不重建**（Window hash 不变），
     *    所以不会整块闪烁；
     * 2. 改窗口尺寸会让高德的地图内容真的重排（全屏 ↔ 卡片切换时能看到缩放变化）。
     *
     * 做法就是把宽度加几个像素、200ms 后再改回来。这点变化肉眼看不出来，
     * 但对高德来说是一次实实在在的布局变更，足以触发它重算导航视口。
     *
     * @param widenPx 临时加宽多少像素。太小可能被高德忽略，3px 足够且无感
     * @param restoreMs 保持临时尺寸多久
     */
    fun nudge(context: Context, rect: Rect, widenPx: Int = 3, restoreMs: Long = 220L): Boolean {
        if (rect.width() <= 0 || rect.height() <= 0) return false
        // 没在显示（进全屏导航、桌面不可见）时抖窗口没有意义，还会把窗口意外唤出来
        if (!shown) {
            Log.i(TAG, "nudge 跳过：悬浮窗当前未显示")
            return false
        }
        nudgePending?.let { main.removeCallbacks(it) }

        val wide = Rect(rect.left, rect.top, rect.right + widenPx, rect.bottom)
        pushRaw(context, wide)
        val back = Runnable {
            nudgePending = null
            pushRaw(context, rect)
            Log.i(TAG, "nudge 恢复 ${rect.width()}x${rect.height()}")
        }
        nudgePending = back
        main.postDelayed(back, restoreMs)
        Log.i(TAG, "nudge 触发 ${rect.width()} → ${wide.width()}（${restoreMs}ms 后恢复）")
        return true
    }

    /** 按给定矩形直接推一次，不碰 shown/lastRect 状态机 */
    private fun pushRaw(context: Context, rect: Rect) {
        val support = probe(context)
        val intent = Intent(ACTION_SHOW).apply {
            support.packages.firstOrNull()?.let { setPackage(it) }
            putExtra("x", rect.left)
            putExtra("y", rect.top)
            putExtra("w", rect.right)
            putExtra("h", rect.bottom)
        }
        runCatching { context.sendBroadcast(intent) }
            .onFailure { Log.w(TAG, "pushRaw 失败：$it") }
    }

    /** 请求隐藏。幂等：没显示过就不发 */
    fun hide(context: Context) {
        nudgePending?.let { main.removeCallbacks(it) }
        nudgePending = null
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

    /** 最近一次成功推给高德的窗口矩形；零坐标模式或从未显示时为 null */
    fun currentRect(): Rect? = lastRect?.let { Rect(it) }

    /** 给设置页用的一句话 */
    fun summary(context: Context): String = probe(context).describe
}
