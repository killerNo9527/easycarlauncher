package com.lf.launcher.map

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lf.launcher.settings.Defaults
import com.lf.launcher.settings.Keys
import com.lf.launcher.settings.SettingsStore

/**
 * 把桌面的昼 / 夜状态推给高德车机版。
 *
 * ## 为什么这条路能走通
 *
 * 高德车机版公开了一整套「AmapAuto 标准广播协议」，本来是给车机厂商做深度定制用的。
 * 其中昼夜那一项是：
 *
 * ```
 * action  = AUTONAVI_STANDARD_BROADCAST_RECV
 * KEY_TYPE             = 10048
 * EXTRA_DAY_NIGHT_MODE = 0 自动 / 1 白天 / 2 黑夜
 * ```
 *
 * 不校验调用方包名、不要任何权限，收到就切图面配色。
 *
 * 实测（模拟器 Android 11 + 高德车机版）：地图区亮度 `218 → 42 → 218`，
 * 昼 → 夜 → 昼 完全可逆；高德退到后台（桌面在前台）时发同样生效。
 *
 * ## 为什么不走系统 uiMode
 *
 * 那条路对高德无效，两个原因叠在一起：
 *
 * 1. 桌面自己的日夜是 Context 级 `uiMode` 覆盖（见 [com.lf.launcher.ui.Appearance]），
 *    只作用于本进程，改不了别的 App；
 * 2. 高德车机版 `targetSdk=23`，不参与 Android 10 起的系统深色模式，
 *    `cmd uimode night yes` 之后它照样白底。
 *
 * 高德自己的「自动」也不看 `uiMode`——它按大灯信号和日出日落判断，
 * 所以和桌面永远对不上。广播是唯一能真正对齐的一条通道。
 *
 * ## 只推白天 / 黑夜，不推「自动」
 *
 * 推 0 等于把开关权还回高德，它立刻又按自己的判断走，和桌面重新脱节。
 * 用户开了这个开关，意思就是「桌面的日夜说了算」。
 */
object AmapDayNight {

    private const val TAG = "AmapDayNight"

    const val AMAP_PKG = "com.autonavi.amapauto"
    private const val AMAP_RECEIVER =
        "com.autonavi.amapauto.adapter.internal.AmapAutoBroadcastReceiver"
    private const val ACTION_RECV = "AUTONAVI_STANDARD_BROADCAST_RECV"
    private const val KEY_TYPE_DAY_NIGHT = 10048
    private const val MODE_DAY = 1
    private const val MODE_NIGHT = 2

    /** 上一次推送的值；进程内去重，避免 onResume 反复发广播 */
    private var lastPushed: Boolean? = null

    fun enabled(): Boolean = SettingsStore.getBool(Keys.AMAP_DAY_NIGHT, Defaults.AMAP_DAY_NIGHT)

    /**
     * 按 [night] 切换高德的图面昼夜。
     *
     * @param force 跳过去重。设置页里「手动同步一次」用它
     * @return 只是"广播已发出"，**不代表高德真的响应了**——
     *         普通 `sendBroadcast` 拿不到接收方的处理结果。高德版本太老
     *         （低于 1.4.2）或装的是手机版时，这里照样返回 true 但画面不会变。
     */
    fun apply(context: Context, night: Boolean, force: Boolean = false): Boolean {
        if (!enabled()) {
            // 开关关掉时清掉去重状态：下次再打开必须能重新推一遍，
            // 否则"关掉 → 打开"这一轮会因为值没变而静默跳过
            lastPushed = null
            return false
        }
        if (!force && lastPushed == night) return false

        val mode = if (night) MODE_NIGHT else MODE_DAY
        val intent = Intent(ACTION_RECV).apply {
            // 显式组件：Android 8 起隐式广播基本收不到，必须点名收件人
            component = ComponentName(AMAP_PKG, AMAP_RECEIVER)
            putExtra("KEY_TYPE", KEY_TYPE_DAY_NIGHT)
            putExtra("EXTRA_DAY_NIGHT_MODE", mode)
            // 高德被强停过（或刚开机还没起来）时，静态接收器默认是沉睡的，
            // 这个 flag 允许把消息递进去顺带唤起它
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        return runCatching {
            context.applicationContext.sendBroadcast(intent)
            lastPushed = night
            Log.i(TAG, "push night=$night (EXTRA_DAY_NIGHT_MODE=$mode)")
            true
        }.getOrElse {
            Log.w(TAG, "push failed", it)
            false
        }
    }

    /** 用户把开关关掉后，让下次开起来能重新推一遍 */
    fun reset() {
        lastPushed = null
    }
}
