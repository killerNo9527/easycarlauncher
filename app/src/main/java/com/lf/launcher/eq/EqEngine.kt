package com.lf.launcher.eq

import android.util.Log
import com.lf.launcher.peq.EqPresets
import com.lf.launcher.peq.EqProfile
import com.lf.launcher.peq.SystemEq

/**
 * 全局 EQ 的运行时句柄。
 *
 * 数据路径：UI 改增益 → [apply] → [SystemEq] → `DynamicsProcessing`（session 0）→ audio HAL。
 * effect 运算在 HAL 侧，不占 App 的 CPU，也不需要自己处理 PCM。
 *
 * 几个实测得出的硬约束（都踩过）：
 * 1. `variant` 必须用 `FREQUENCY_RESOLUTION(=0)`，`TIME_RESOLUTION(=1)` 在这类设备上
 *    直接抛 "bad parameter value"，31 段建不起来；
 * 2. `DynamicsProcessing` 构造完默认 `enabled=false`，必须显式 `setEnabled(true)`；
 * 3. `session 0` 是全局混音，但厂商 ROM 可能拒绝 —— [create] 返回 null 时 UI 要能降级提示，
 *    不能假装生效；
 * 4. 一个 App 只保留一个实例，反复 create 会在 AudioFlinger 里堆 effect。
 */
object EqEngine {

    private const val TAG = "EqEngine"

    /** 全局输出混音 */
    private const val GLOBAL_SESSION = 0

    private var sysEq: SystemEq? = null

    /** 诊断只跑一次，避免每次 ensure 失败都刷屏 */
    private var diagnosed = false

    /** 上一次失败的原因，UI 直接显示 */
    var lastError: String? = null
        private set

    /** 当前是否真的把 EQ 打开了（构造成功 + setEnabled(true)） */
    var active: Boolean = false
        private set

    /** 是否已经成功建过 effect 实例 */
    val created: Boolean get() = sysEq != null

    /**
     * 下发一组增益并设置开关。
     *
     * @return true 表示 effect 已建好且状态已下发；false 看 [lastError]
     */
    fun apply(gains: FloatArray, enabled: Boolean, preampDb: Float = 0f): Boolean {
        val eq = ensure() ?: return false
        return try {
            eq.applyProfile(EqPresets.profile(gains, preampDb = preampDb))
            eq.setEnabled(enabled)
            remember(gains)
            active = enabled
            lastError = null
            Log.i(TAG, "apply ok enabled=$enabled bands=${gains.size} preamp=$preampDb")
            true
        } catch (t: Throwable) {
            lastError = "写入失败：${t.javaClass.simpleName} ${t.message ?: ""}"
            active = false
            Log.w(TAG, "apply failed", t)
            false
        }
    }

    /** 只切开关，不动曲线（页面关掉再打开时用） */
    fun setEnabled(enabled: Boolean): Boolean {
        val eq = sysEq ?: return apply(currentProfileGains(), enabled)
        return try {
            eq.setEnabled(enabled)
            active = enabled
            lastError = null
            true
        } catch (t: Throwable) {
            lastError = "开关失败：${t.javaClass.simpleName}"
            false
        }
    }

    /**
     * 只建实例、不开效果。页面一打开就调，用来尽早告诉用户本机支不支持，
     * 免得开了开关才发现是空的。
     */
    fun probe(): Boolean = ensure() != null

    fun release() {
        sysEq?.let { runCatching { it.release() } }
        sysEq = null
        active = false
    }

    /** 上次 apply 的曲线，切开关时若实例已在就不必重算 */
    private var lastGains: FloatArray? = null

    private fun currentProfileGains(): FloatArray = lastGains ?: EqPresets.FLAT

    private fun ensure(): SystemEq? {
        sysEq?.let { return it }
        val created = SystemEq.create(
            channelCount = 2,
            bandCount = EqPresets.BAND_COUNT,
            sessionId = GLOBAL_SESSION,
        )
        if (created == null) {
            lastError = "本机不支持全局 EQ（DynamicsProcessing 创建失败）"
            Log.w(TAG, "SystemEq.create returned null")
            if (!diagnosed) {
                diagnosed = true
                SystemEq.diagnose()
            }
            return null
        }
        sysEq = created
        Log.i(TAG, "SystemEq created bands=${created.bandCount} session=${created.sessionId}")
        return created
    }

    /** UI 用来显示的一行状态 */
    fun statusText(): String = when {
        lastError != null -> lastError!!
        !created -> "未启用"
        active -> "全局生效中（31 段）"
        else -> "已就绪，未开启"
    }

    fun remember(gains: FloatArray) {
        lastGains = gains.copyOf()
    }

    fun rememberProfile(profile: EqProfile) {
        lastGains = EqPresets.gainsOfProfile(profile)
    }
}
