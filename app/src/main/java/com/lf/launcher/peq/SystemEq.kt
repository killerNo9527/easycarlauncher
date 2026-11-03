package com.lf.launcher.peq

import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.annotation.TargetApi
import android.util.Log
import kotlin.math.max

/**
 * 系统级（"全局"）EQ —— 基于 `android.media.audiofx.DynamicsProcessing`。
 *
 * ## 为什么是它
 * Android 自带的 `Equalizer` 效果器频段数是**设备写死的**（常见 5 段，少数 10 段），
 * 频点由厂商音效库决定，第三方改不了。真正能"自定义频段数量与频点"的官方 API 只有
 * API 28 引入的 `DynamicsProcessing`：`EqBand` 由调用方给出
 * `(enabled, cutoffFrequency, gain)`，数量在 `Config.Builder` 里自由指定。
 *
 * ## 能力边界（重要）
 * - `DynamicsProcessing.EqBand` **只有 cutoffFrequency + gain，没有 Q**。
 *   所以它能做"任意数量、任意频点的图示均衡"，**做不了 Q 可调的参量均衡**。
 *   这正是 Poweramp 同时保留自研 DSP（`libpeqcore.so`）的原因：它的 Parametric 模式
 *   （资源文案 "each band is added and configured manually" + "Q factor"）必须自己算 biquad。
 *   → 只要多段图示 EQ：用本类。要 Q 可调：用 [ParametricEq]。
 * - 挂 **session 0** 才作用于全局输出混音，需要 `MODIFY_AUDIO_SETTINGS`，
 *   且部分厂商 ROM 会拒绝第三方应用（构造时抛异常）。失败就降级挂自己的播放会话，
 *   或退回 [ParametricEq] 在自家音频链里处理。
 * - 实际运算在 audio HAL，不占你的 CPU，也不需要自己 resample。
 *
 * ## 用法
 * ```kotlin
 * // 全局
 * val sysEq = SystemEq.create(channelCount = 2, bandCount = 12, sessionId = 0)
 *     ?: fallbackToParametric()
 * sysEq.applyProfile(EqProfile.graphic(12))
 * sysEq.setEnabled(true)
 * // 切换到底层播放器会话（sessionId 来自 AudioTrack.audioSessionId）
 * sysEq.release()
 * val local = SystemEq.create(channelCount = 2, bandCount = 12, sessionId = playerSessionId)
 * ```
 */
@TargetApi(Build.VERSION_CODES.P)
class SystemEq private constructor(
    val channelCount: Int,
    val bandCount: Int,
    val sessionId: Int,
    private val limiterInUse: Boolean,
    private val dp: DynamicsProcessing,
) {
    var enabled: Boolean = false
        private set

    /**
     * 把频段写进效果器。
     *
     * 频段按频率**从低到高**排序后传入（顺序错乱时 HAL 侧行为未定义）。
     * 超过 [bandCount] 的频段会被忽略——请在创建时按上限声明数量。
     */
    fun applyProfile(profile: EqProfile) {
        val sorted = profile.bands.filter { it.enabled }.sortedBy { it.freqHz }
        val eq = DynamicsProcessing.Eq(true, true, bandCount)
        for (i in 0 until bandCount) {
            val band = sorted.getOrNull(i)
            // 关键：DynamicsProcessing.EqBand 的 cutoffFrequency 是**频段上边界**，不是中心频率。
            // 第 i 段实际作用区间是 [上一段 cutoff, 本段 cutoff]。
            //
            // 之前这里直接把 EqPresets 的中心频率（20 / 25 / 31.5 … 20000）当 cutoff 传进去，
            // 于是「800Hz 那一档 +3dB」实际作用在 [630, 800] 上，比预期整体低约半个档位
            // （1/6 倍频程），31 段的精细曲线会走形，越是低频端偏得越多（第一段 cutoff=20Hz
            // 等于只作用于 20Hz 以下，几乎不可闻）。
            //
            // 正确做法：上边界取「本段中心」与「下一段中心」的几何中点，
            // 这样 [上半边界, 下半边界] 的几何中心正好落在本段中心频率上。
            val f = band?.freqHz ?: defaultFreq(i)
            val upper = sorted.getOrNull(i + 1)?.freqHz?.let { next ->
                kotlin.math.sqrt(f * next)
            } ?: f
            eq.setBand(
                i,
                DynamicsProcessing.EqBand(
                    band != null,
                    upper,
                    (band?.gainDb ?: 0f) + profile.preampDb,
                ),
            )
        }
        dp.setPostEqAllChannelsTo(eq)
        // 读回一次：写入成功 ≠ 设备接受。HAL 拒绝时 setPostEq 不会抛异常，
        // 只会把配置丢掉，所以必须回读确认（这里是唯一能发现"假装生效"的地方）。
        runCatching {
            val back = dp.getPostEqByChannelIndex(0)
            if (back != null) {
                val mid = bandCount / 2
                val b = back.getBand(mid)
                Log.i(
                    "SystemEq",
                    "apply readback bands=${back.bandCount} mid=#${mid} " +
                        String.format(java.util.Locale.US, "%.0fHz/%.1fdB",
                            b.cutoffFrequency, b.gain),
                )
            } else {
                Log.w("SystemEq", "apply readback null")
            }
        }
        if (limiterInUse) {
            // 多段正增益叠加容易削顶。默认关：20:1 压缩对音乐是音质损失，
            // 要防削顶用 preampDb 预留 headroom 更干净。
            dp.setLimiterAllChannelsTo(
                DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, -3f, 0f, 20f)
            )
        }
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        dp.setEnabled(on)
        // 回读：某些 ROM 上 setEnabled 不抛异常但也不生效（AudioFlinger 里仍是 DISABLED），
        // 只有读回来才知道到底开没开
        Log.i("SystemEq", "setEnabled($on) readback=${dp.enabled}")
    }

    fun release() {
        runCatching { dp.setEnabled(false) }
        runCatching { dp.release() }
        enabled = false
    }

    private fun defaultFreq(index: Int): Float {
        val t = if (bandCount <= 1) 0.0 else index.toDouble() / (bandCount - 1)
        return (20.0 * Math.pow(1000.0, t)).toFloat()
    }

    companion object {
        const val VARIANT_FAVOR_FREQUENCY_RESOLUTION = DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION
        const val VARIANT_FAVOR_TIME_RESOLUTION = DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION

        /** session 0 = 全局输出混音 */
        const val GLOBAL_SESSION = 0

        /**
         * 创建系统 EQ。session 在构造时绑定，要换 session 就 release 后重新 create。
         *
         * @param sessionId 0 = 全局；>0 = 指定播放会话（如 `audioTrack.audioSessionId`）
         * @param variant 频段多（>10）时用 [VARIANT_FAVOR_FREQUENCY_RESOLUTION]
         * @return 设备不支持或 ROM 拒绝时返回 null，请降级
         */
        @JvmStatic
        @JvmOverloads
        fun create(
            channelCount: Int = 2,
            bandCount: Int = 10,
            sessionId: Int = GLOBAL_SESSION,
            // 实测（车机 Android 9 / 雷电 Android 9）：只有 VARIANT_FAVOR_FREQUENCY_RESOLUTION(=0)
            // 能建起来并写入 31 段；VARIANT_FAVOR_TIME_RESOLUTION(=1) 一律抛 "bad parameter value"。
            // 注意两个常量的值容易记反（FREQ=0 / TIME=1），以 javap -constants 为准。
            variant: Int = VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            limiterInUse: Boolean = false,
            priority: Int = 0,
        ): SystemEq? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
            if (bandCount !in 1..64) return null
            return runCatching {
                val cfg = DynamicsProcessing.Config.Builder(
                    variant,
                    channelCount,
                    /* preEqInUse = */ false, /* preEqBandCount = */ 0,
                    /* mbInUse = */ false, /* mbBandCount = */ 0,
                    /* postEqInUse = */ true, /* postEqBandCount = */ bandCount,
                    /* limiterInUse = */ limiterInUse,
                ).build()
                SystemEq(
                    channelCount, bandCount, sessionId, limiterInUse,
                    DynamicsProcessing(priority, sessionId, cfg),
                )
            }.onFailure {
                android.util.Log.w("SystemEq", "create failed: ${it.javaClass.simpleName} ${it.message}")
            }.getOrNull()
        }

        /** 按频段正增益总量估算建议 headroom（dB，<=0） */
        fun suggestedHeadroomDb(bands: List<EqBand>): Float =
            -bands.filter { it.enabled }.sumOf { max(0f, it.gainDb).toDouble() }
                .toFloat().coerceIn(-24f, 0f)

        /**
         * 诊断：把 Config.Builder 与构造两个阶段拆开打日志，并跑一组组合矩阵。
         * 只在创建失败时由 [EqEngine] 调一次，结果进 logcat tag = SystemEq。
         */
        @JvmStatic
        fun diagnose(sessionId: Int = GLOBAL_SESSION) {
            val tag = "SystemEq"
            Log.i(tag, "diagnose start sdk=${Build.VERSION.SDK_INT} session=$sessionId")
            val combos = listOf(
                Triple(VARIANT_FAVOR_FREQUENCY_RESOLUTION, 31, true),
                Triple(VARIANT_FAVOR_FREQUENCY_RESOLUTION, 31, false),
                Triple(VARIANT_FAVOR_FREQUENCY_RESOLUTION, 20, true),
                Triple(VARIANT_FAVOR_FREQUENCY_RESOLUTION, 10, false),
                Triple(VARIANT_FAVOR_TIME_RESOLUTION, 31, true),
            )
            for ((variant, bands, limiter) in combos) {
                val label = "v=$variant bands=$bands lim=$limiter"
                val cfg = try {
                    DynamicsProcessing.Config.Builder(
                        variant, 2,
                        false, 0, false, 0,
                        true, bands, limiter,
                    ).build()
                } catch (t: Throwable) {
                    Log.w(tag, "  [$label] build FAIL ${t.javaClass.simpleName} ${t.message}")
                    continue
                }
                try {
                    val dp = DynamicsProcessing(0, sessionId, cfg)
                    Log.i(tag, "  [$label] OK id=${dp.id} postEq=${cfg.postEqBandCount}")
                    runCatching { dp.release() }
                } catch (t: Throwable) {
                    Log.w(tag, "  [$label] ctor FAIL ${t.javaClass.simpleName} ${t.message}")
                }
            }
            Log.i(tag, "diagnose end")
        }
    }
}
