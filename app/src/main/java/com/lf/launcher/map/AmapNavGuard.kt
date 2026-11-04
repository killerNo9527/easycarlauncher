package com.lf.launcher.map

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log

/**
 * 监听高德「开始导航 / 开始巡航」的那一刻。
 *
 * ## 为什么需要
 *
 * 高德的悬浮窗在**进入导航那一帧**会把视口算错：自车不居中、画面右侧被裁、
 * 缩放偏大。而手动点一次「全览」再让高德自己回到导航，一切就正常了。
 * 这说明它有能力算对，只是那个转换点没重算。
 *
 * [AmapPlusController.nudge] 负责把窗口抖一下逼它重排，本对象负责判断"什么时候抖"。
 *
 * ## 用什么信号
 *
 * 高德车机版会持续往外发 `AUTONAVI_STANDARD_BROADCAST_SEND`，其中
 * `KEY_TYPE=10001` 那一帧是导航/巡航实时信息（转向图标、车速、剩余距离…），
 * 导航中每秒好几条。所以：
 *
 * - 收到 10001 → 高德在导航或巡航；
 * - 距离上一条**超过 [SESSION_GAP_MS]** 才算"新开始的一次"，避免每帧都抖窗口。
 *
 * ## 为什么不用 10019（导航状态通知）
 *
 * 那个接口只在真正进入导航时发一次，看着更精准，但它不是所有改版/版本都实现；
 * 10001 是导航数据的主动脉，稳定性高得多。巡航时也会触发一次重排，无副作用。
 */
object AmapNavGuard {

    private const val TAG = "AmapNavGuard"

    const val ACTION_SEND = "AUTONAVI_STANDARD_BROADCAST_SEND"
    private const val KEY_TYPE_NAVI_INFO = 10001

    /** 两帧之间超过这个间隔，就认为中间断过、这次是新的一段导航 */
    private const val SESSION_GAP_MS = 3_000L

    private var receiver: BroadcastReceiver? = null
    private var lastAt = 0L

    /** 新一段导航/巡航开始时回调（主线程） */
    var onNavStart: (() -> Unit)? = null

    fun start(context: Context) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent == null) return
                if (intent.getIntExtra("KEY_TYPE", -1) != KEY_TYPE_NAVI_INFO) return
                val now = SystemClock.elapsedRealtime()
                val gap = now - lastAt
                lastAt = now
                if (gap > SESSION_GAP_MS) {
                    Log.i(TAG, "导航/巡航开始（静默 ${gap}ms 后收到首帧）")
                    onNavStart?.invoke()
                }
            }
        }
        runCatching {
            context.applicationContext.registerReceiver(r, IntentFilter(ACTION_SEND))
            receiver = r
            Log.i(TAG, "开始监听 $ACTION_SEND")
        }.onFailure { Log.w(TAG, "注册失败：$it") }
    }

    fun stop(context: Context) {
        receiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        receiver = null
        lastAt = 0L
    }

    fun isRunning(): Boolean = receiver != null
}
