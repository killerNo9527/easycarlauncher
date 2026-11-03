package com.lf.launcher.peq

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 双二阶（biquad）滤波器系数与频响计算。
 *
 * 公式来源：Robert Bristow-Johnson《Audio EQ Cookbook》——公开的通用 DSP 公式，
 * 与具体产品实现无关。所有系数已按 a0 归一化。
 *
 * Poweramp 把这一步放在 native 里（libpeqcore.so 内的 `native_calc_bands_coefs`、
 * `native_modify_coefs_for_gain`），这里用纯 Kotlin 复刻同等能力，便于移植且无 JNI 负担。
 */
class Biquad private constructor(
    @JvmField val b0: Float,
    @JvmField val b1: Float,
    @JvmField val b2: Float,
    @JvmField val a1: Float,
    @JvmField val a2: Float,
) {
    /** 该滤波器在某频率处的幅度响应（dB）。用于绘制 EQ 曲线，不做实际处理。 */
    fun magnitudeDb(sampleRateHz: Int, freqHz: Float): Float {
        val w = 2.0 * Math.PI * freqHz / sampleRateHz
        val cw = cos(w); val sw = sin(w)
        val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = b0 + b1 * cw + b2 * c2
        val ni = -(b1 * sw + b2 * s2)
        val dr = 1.0 + a1 * cw + a2 * c2
        val di = -(a1 * sw + a2 * s2)
        val mag = hypot(nr, ni) / hypot(dr, di)
        return (20.0 * log10(mag)).toFloat()
    }

    /** 极点半径，<1 稳定。可用于自检/防御。 */
    fun poleRadius(): Float = sqrt(abs(a2))

    companion object {
        /** 旁路（直通）系数 */
        val BYPASS = Biquad(1f, 0f, 0f, 0f, 0f)

        /**
         * 计算一个频段在给定采样率下的系数。
         *
         * @param sampleRateHz 采样率，必须与音频输出一致（Poweramp 也是按实际输出采样率重算）
         * @throws IllegalArgumentException 采样率或参数非法
         */
        fun design(
            type: BandType,
            sampleRateHz: Int,
            freqHz: Float,
            q: Float,
            gainDb: Float,
        ): Biquad {
            require(sampleRateHz > 0) { "sampleRateHz 必须 > 0" }
            // 中心频率不得超过 Nyquist 的 99%，否则三角运算退化、系数失去意义
            val nyquist = sampleRateHz / 2f
            val f0 = freqHz.coerceIn(1f, nyquist * 0.99f)
            val safeQ = q.coerceIn(0.05f, 40f)

            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * Math.PI * f0 / sampleRateHz
            val cw = cos(w0)
            val sw = sin(w0)
            // 架式滤波器用 S=1 的带宽定义（斜率固定且不会出现增益过冲）
            val alphaShelf = (sw / 2) * sqrt((a + 1 / a) * (1.0 / 1.0 - 1) + 2)
            val alpha = sw / (2 * safeQ)

            var b0: Double
            var b1: Double
            var b2: Double
            var a0: Double
            var a1: Double
            var a2: Double

            when (type) {
                BandType.PEAK -> {
                    b0 = 1 + alpha * a; b1 = -2 * cw; b2 = 1 - alpha * a
                    a0 = 1 + alpha / a; a1 = -2 * cw; a2 = 1 - alpha / a
                }
                BandType.LOW_SHELF -> {
                    val sa = sqrt(a)
                    b0 = a * ((a + 1) - (a - 1) * cw + 2 * sa * alphaShelf)
                    b1 = 2 * a * ((a - 1) - (a + 1) * cw)
                    b2 = a * ((a + 1) - (a - 1) * cw - 2 * sa * alphaShelf)
                    a0 = (a + 1) + (a - 1) * cw + 2 * sa * alphaShelf
                    a1 = -2 * ((a - 1) + (a + 1) * cw)
                    a2 = (a + 1) + (a - 1) * cw - 2 * sa * alphaShelf
                }
                BandType.HIGH_SHELF -> {
                    val sa = sqrt(a)
                    b0 = a * ((a + 1) + (a - 1) * cw + 2 * sa * alphaShelf)
                    b1 = -2 * a * ((a - 1) + (a + 1) * cw)
                    b2 = a * ((a + 1) + (a - 1) * cw - 2 * sa * alphaShelf)
                    a0 = (a + 1) - (a - 1) * cw + 2 * sa * alphaShelf
                    a1 = 2 * ((a - 1) - (a + 1) * cw)
                    a2 = (a + 1) - (a - 1) * cw - 2 * sa * alphaShelf
                }
                BandType.BAND_PASS -> {
                    b0 = alpha; b1 = 0.0; b2 = -alpha
                    a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha
                }
                BandType.NOTCH -> {
                    b0 = 1.0; b1 = -2 * cw; b2 = 1.0
                    a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha
                }
                BandType.LOW_PASS -> {
                    b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = (1 - cw) / 2
                    a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha
                }
                BandType.HIGH_PASS -> {
                    b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = (1 + cw) / 2
                    a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha
                }
            }

            return Biquad(
                (b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(),
                (a1 / a0).toFloat(), (a2 / a0).toFloat(),
            )
        }

        /** 增益为 0 的频段直接返回旁路，省掉一次乘加（对应 `native_modify_coefs_for_gain` 的优化意图） */
        fun designOrBypass(type: BandType, sampleRateHz: Int, freqHz: Float, q: Float, gainDb: Float): Biquad {
            if (type.usesGainDb && abs(gainDb) < 0.01f) return BYPASS
            return design(type, sampleRateHz, freqHz, q, gainDb)
        }
    }
}
