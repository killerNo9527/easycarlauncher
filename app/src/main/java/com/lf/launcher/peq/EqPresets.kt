package com.lf.launcher.peq

/**
 * 内置预设。
 *
 * 频段数固定 31（1/3 倍频程网格，20Hz~20kHz），因为上游 `DynamicsProcessing` 的
 * `EqBand` 只吃 `cutoffFrequency + gain`，正好一格一段；31 段对这套网格的还原度
 * 实测 RMS 0.022 dB，已经远低于可闻阈值。
 */
object EqPresets {

    /** 31 段 1/3 倍频程中心频率，与 [GRAPHIC_BANDS] 一一对应 */
    val FREQS = floatArrayOf(
        20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f,
        200f, 250f, 315f, 400f, 500f, 630f, 800f, 1000f, 1250f, 1600f,
        2000f, 2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 10000f, 12500f, 16000f,
        20000f,
    )

    val BAND_COUNT: Int get() = FREQS.size

    /**
     * 车机矫正曲线：由用户实测的 20 点矫正数据拟合到 31 段
     * （L2 最小二乘，1/3 倍频程 peaking 基函数，RMS 0.022 dB / 最大 0.04 dB）。
     *
     * 原始 20 点：20:+1.5  31:+0.5  45:-1.0  63:-0.5  80:-2.5  125:-2.0  250:-0.5
     *            500:+1.0  800:-1.0  1000:-0.5  1250:-1.0  2000:+0.5  3150:0.0
     *            4000:-1.5  6300:-1.5  8000:0.0  10000:-1.0  12500:-0.5
     *            16000:-0.5  20000:-1.0
     */
    val CAR_CORRECTION = floatArrayOf(
        1.4f, 0.4f, 0.6f, -0.8f, -1.0f, 0.2f, -2.2f, -0.7f, -1.6f, -0.3f,
        -0.2f, -0.4f, 0.0f, 0.2f, 1.0f, 0.0f, -1.0f, -0.1f, -0.9f, -0.1f,
        0.6f, 0.1f, 0.2f, -1.4f, -0.4f, -1.4f, 0.3f, -0.9f, -0.4f, -0.5f,
        -1.0f,
    )

    /** 平直：全 0 */
    val FLAT = FloatArray(BAND_COUNT)

    /**
     * 预设清单：id → (名称, 增益数组)
     * 自定义预设不入此表，由调用方从 SettingsStore 读。
     */
    val BUILT_IN: List<Triple<String, String, FloatArray>> = listOf(
        Triple("flat", "平直", FLAT),
        Triple("car", "车机矫正", CAR_CORRECTION),
    )

    fun gainsOf(id: String): FloatArray? = BUILT_IN.firstOrNull { it.first == id }?.third

    fun nameOf(id: String): String = BUILT_IN.firstOrNull { it.first == id }?.second ?: "自定义"

    /** 由增益数组构造 31 段 profile */
    fun profile(gains: FloatArray, name: String = "Custom", preampDb: Float = 0f): EqProfile {
        val bands = ArrayList<EqBand>(BAND_COUNT)
        for (i in 0 until BAND_COUNT) {
            bands += EqBand(
                type = BandType.PEAK,
                freqHz = FREQS[i],
                q = 4.33f,          // 1/3 倍频程带宽
                gainDb = gains.getOrElse(i) { 0f },
                name = "",
            )
        }
        return EqProfile(name = name, bands = bands, preampDb = preampDb)
    }

    /** 把 profile 还原成 31 个增益（按 FREQS 对齐，缺的补 0） */
    fun gainsOfProfile(profile: EqProfile): FloatArray {
        val out = FloatArray(BAND_COUNT)
        for (i in 0 until BAND_COUNT) {
            val f = FREQS[i]
            val band = profile.bands.minByOrNull { kotlin.math.abs(it.freqHz - f) }
            out[i] = if (band != null && kotlin.math.abs(band.freqHz - f) < 0.5f) band.gainDb else 0f
        }
        return out
    }
}
