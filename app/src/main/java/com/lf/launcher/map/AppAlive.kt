package com.lf.launcher.map

import android.app.ActivityManager
import android.content.Context
import android.util.Log

/**
 * 第三方应用「还活着吗」。
 *
 * ## 为什么不能直接信 [ActivityManager.getRunningAppProcesses]
 *
 * 从 Android 5.1（API 22）起，这个方法**只返回调用者自己的进程**，官方文档写得很明确。
 * 但各 ROM 的实现差异很大，有些仍然返回全量。所以这里返回的是三态：
 *
 * - `true`  确认在跑
 * - `false` 确认没在跑（列表里能看见别的第三方应用，说明这个 ROM 没限制）
 * - `null`  判断不了（列表只有自己 → 受限了）
 *
 * 调用方拿到 `null` 时必须自己决定兜底策略，**不能当成"没在跑"** —— 否则每次判断
 * 都会去重新拉起一次，比不检测还糟。
 */
object AppAlive {

    private const val TAG = "AppAlive"

    /**
     * @return true 在跑 / false 不在跑 / null 这个 ROM 不给看
     */
    fun isRunning(ctx: Context, pkg: String): Boolean? {
        if (pkg.isEmpty()) return false
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val procs = runCatching { am.runningAppProcesses }.getOrNull()
        if (procs.isNullOrEmpty()) {
            Log.i(TAG, "runningAppProcesses 返回空，无法判断")
            return null
        }

        val hit = procs.any { it.processName == pkg || it.processName.startsWith("$pkg:") }

        // 判断"这个 ROM 到底给不给看"：列表里除了自己还有别的第三方进程，就说明没被限制。
        // 只剩自己一个的时候，"没找到目标"和"看不到目标"是一回事，不能下结论
        val selfPkg = ctx.packageName
        val others = procs.count {
            val n = it.processName.substringBefore(':')
            n != selfPkg && n != "android" && n != "com.android.systemui"
        }
        val trustworthy = hit || others > 0

        Log.i(
            TAG,
            "查询 $pkg 存活：hit=$hit 列表=${procs.size} 其他第三方=$others " +
                "→ ${if (!trustworthy) "无法判断" else hit.toString()}",
        )
        return if (trustworthy) hit else null
    }
}
