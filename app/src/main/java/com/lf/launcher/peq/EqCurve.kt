package com.lf.launcher.peq

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * 频响曲线计算：给 UI 画 EQ 曲线用。
 *
 * Poweramp 这活儿也在 native（`native_filter_evaluate_freqs` + `PeqVisualizatorExecutor.cpp`），
 * 好处是曲线和实际处理共用同一套系数，不会"画的是一套、听的是另一套"。
 * 这里用 [Biquad.magnitudeDb] 在相同的系数上求值，等价但不需要 JNI。
 */
object EqCurve {

    /** 某个频率处的总响应（dB），含前级增益 */
    fun responseDb(profile: EqProfile, sampleRateHz: Int, freqHz: Float): Float {
        var db = profile.preampDb
        for (band in profile.bands) {
            if (!band.enabled) continue
            val gain = band.gainDb
            if (band.type.usesGainDb && abs(gain) < 0.01f) continue
            val c = Biquad.design(band.type, sampleRateHz, band.freqHz, band.q, gain)
            db += c.magnitudeDb(sampleRateHz, freqHz)
        }
        return db
    }

    /**
     * 生成对数等间距的曲线采样点。
     * @return FloatArray，长度 [points]，值为 dB
     */
    fun curve(
        profile: EqProfile,
        sampleRateHz: Int,
        points: Int = 128,
        fMin: Float = 20f,
        fMax: Float = 20000f,
    ): FloatArray {
        require(points >= 2)
        val out = FloatArray(points)
        val lo = log10(fMin.toDouble())
        val hi = log10(fMax.toDouble())
        for (i in 0 until points) {
            val t = i.toDouble() / (points - 1)
            val f = 10.0.pow(lo + (hi - lo) * t).toFloat()
            out[i] = responseDb(profile, sampleRateHz, f)
        }
        return out
    }

    /** 曲线的最大/最小 dB，用于确定画布纵轴范围 */
    fun rangeDb(profile: EqProfile, sampleRateHz: Int): Pair<Float, Float> {
        val c = curve(profile, sampleRateHz)
        return (c.minOrNull() ?: 0f) to (c.maxOrNull() ?: 0f)
    }

    /** 找出响应最突出的频率（用于 "Bands Overlap" 之类的提示） */
    fun peakFrequency(profile: EqProfile, sampleRateHz: Int): Float? {
        val points = 256
        val lo = log10(20.0); val hi = log10(20000.0)
        var bestF = 20f; var bestDb = Float.NEGATIVE_INFINITY
        for (i in 0 until points) {
            val t = i.toDouble() / (points - 1)
            val f = 10.0.pow(lo + (hi - lo) * t).toFloat()
            val db = responseDb(profile, sampleRateHz, f)
            if (db > bestDb) { bestDb = db; bestF = f }
        }
        return if (bestDb > 2f) bestF else null
    }

    /**
     * 相邻频段是否重叠（对应 UI 的 "Bands Overlap" 警告）。
     *
     * 判据：两个峰值频段的中心频率比 < 2^(1/(2Q)) 即认为带宽有交叠。
     */
    fun detectOverlaps(profile: EqProfile): List<Pair<Int, Int>> {
        val peaks = profile.bands.withIndex().filter { it.value.type == BandType.PEAK && it.value.enabled }
        val result = ArrayList<Pair<Int, Int>>()
        for (i in peaks.indices) {
            for (j in i + 1 until peaks.size) {
                val a = peaks[i]; val b = peaks[j]
                val ratio = maxOf(a.value.freqHz, b.value.freqHz) / minOf(a.value.freqHz, b.value.freqHz)
                val bandwidthOctavesA = 1.0 / a.value.q
                val bandwidthOctavesB = 1.0 / b.value.q
                val centerDistanceOctaves = log2(ratio.toDouble())
                if (centerDistanceOctaves < (bandwidthOctavesA + bandwidthOctavesB) / 2) {
                    result += a.index to b.index
                }
            }
        }
        return result
    }

    private fun log2(x: Double): Double = log10(x) / log10(2.0)
}
