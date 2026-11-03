package com.lf.launcher.peq

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.sinh

/**
 * 内置预设 + 频点工具。
 *
 * 频段数固定 31，因为上游 `DynamicsProcessing` 的 `EqBand` 只吃
 * `cutoffFrequency + gain`，一格一段；默认频点取 1/3 倍频程网格
 * （20Hz~20kHz），31 段对它还原度实测 RMS 0.022 dB，远低于可闻阈值。
 *
 * **频点本身是可改的**：`SystemEq.applyProfile` 逐段把 `band.freqHz` 当
 * `cutoffFrequency` 传下去，所以 31 个中心频率可以任意摆——只是数量与升序
 * 不能变（`DynamicsProcessing.Eq` 的段数在 `Config.Builder` 里定死，
 * 换段数要重建 effect）。[FREQS] 只是缺省布局。
 */
object EqPresets {

    /** 频点允许范围（Hz） */
    const val MIN_HZ = 20f
    const val MAX_HZ = 20000f

    /**
     * 相邻频点的最小比值。
     *
     * 两段挤到同一频率会让 `SystemEq` 算出的上边界等于自身（区间宽度 0），
     * HAL 侧行为未定义；1.01 约等于 1/70 倍频程，已经细到听不出来。
     */
    const val MIN_RATIO = 1.01f

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

    /**
     * 由增益数组 + 频点数组构造 31 段 profile。
     *
     * @param freqs 31 个中心频率，缺省用 [FREQS]；会先过一遍 [sanitizeFreqs]
     * @param q     手工指定 Q（null = 按相邻频点间距自适应，等价 1/3 倍频程时约 4.33）
     */
    fun profile(
        gains: FloatArray,
        freqs: FloatArray = FREQS,
        name: String = "Custom",
        preampDb: Float = 0f,
        q: Float? = null,
    ): EqProfile {
        val f = sanitizeFreqs(freqs)
        val bands = ArrayList<EqBand>(BAND_COUNT)
        for (i in 0 until BAND_COUNT) {
            bands += EqBand(
                type = BandType.PEAK,
                freqHz = f[i],
                // Q 只用于本地画曲线（系统 EqBand 没有 Q 参数，作用区间由 cutoff 几何中点决定）。
                // 频点被拉宽/挤窄后固定 4.33 会让曲线画错，所以按实际间距自适应。
                q = q ?: bandQ(f, i),
                gainDb = gains.getOrElse(i) { 0f },
                name = "",
            )
        }
        return EqProfile(name = name, bands = bands, preampDb = preampDb)
    }

    /**
     * 第 [i] 段的等效 Q：由与前后相邻频点的间距反推带宽。
     *
     * Q = 1 / (2·sinh(ln2/2 · BW))，BW 单位是倍频程。
     * 默认 1/3 倍频程网格算出来约 4.4，和原先写死的 4.33 基本一致，
     * 所以不改频点时曲线和以前一样；把某段挪到别处，曲线会跟着变宽变窄。
     */
    fun bandQ(freqs: FloatArray, i: Int): Float {
        val f = freqs.getOrElse(i) { 1000f }
        val next = freqs.getOrNull(i + 1)?.takeIf { it > f * 1.001f }
        val prev = freqs.getOrNull(i - 1)?.takeIf { it < f / 1.001f }
        // 首末段没有一侧邻居，用另一侧做对数对称，保证带宽仍然合理
        val lo = prev ?: next?.let { f * f / it } ?: (f / 1.26f)
        val hi = next ?: prev?.let { f * f / it } ?: (f * 1.26f)
        val bwOct = (log2((hi / lo).toDouble()) / 2.0).coerceIn(1.0 / 12, 4.0)
        return (1.0 / (2.0 * sinh(ln(2.0) * bwOct / 2.0))).toFloat().coerceIn(0.3f, 18f)
    }

    /**
     * 把外部传入的频点数组规整成"31 个、升序、相邻至少差 [MIN_RATIO]、在 [MIN_HZ]~[MAX_HZ] 内"。
     *
     * 任何一项不合法（数量不对、NaN、非正数、乱序到无法修复）都整体回落到 [FREQS]——
     * 频点错乱会让 `SystemEq` 把 cutoff 算成负值或乱序传给 HAL，宁可丢掉这次编辑。
     */
    fun sanitizeFreqs(input: FloatArray?): FloatArray {
        if (input == null || input.size != BAND_COUNT) return FREQS.copyOf()
        val out = FloatArray(BAND_COUNT)
        for (i in 0 until BAND_COUNT) {
            val raw = input[i]
            out[i] = if (raw.isFinite() && raw > 0f) raw.coerceIn(MIN_HZ, MAX_HZ) else FREQS[i]
        }
        // 从低到高把间距撑开
        for (i in 1 until BAND_COUNT) {
            val minV = out[i - 1] * MIN_RATIO
            if (out[i] < minV) out[i] = minV
        }
        // 上推有可能把末段顶出上限，再从高到低压回来
        if (out[BAND_COUNT - 1] > MAX_HZ) {
            out[BAND_COUNT - 1] = MAX_HZ
            for (i in BAND_COUNT - 2 downTo 0) {
                val maxV = out[i + 1] / MIN_RATIO
                if (out[i] > maxV) out[i] = maxV
            }
        }
        out[0] = out[0].coerceAtLeast(MIN_HZ)
        // 兜底：仍然不严格升序就用默认布局
        for (i in 1 until BAND_COUNT) {
            if (out[i] <= out[i - 1]) return FREQS.copyOf()
        }
        return out
    }

    /** 是否就是默认的 1/3 倍频程布局（用来判断要不要在 UI 上标"自定义频点"） */
    fun isDefaultFreqs(freqs: FloatArray): Boolean {
        if (freqs.size != BAND_COUNT) return false
        for (i in 0 until BAND_COUNT) {
            if (abs(freqs[i] - FREQS[i]) > 0.05f) return false
        }
        return true
    }

    /**
     * 频点显示文本。31 段挤在一行，每个标签只有约 60px，
     * 所以 1k 以上一律缩成 "1.25k" / "12.5k"，不足 1k 保留一位小数（31.5）。
     */
    fun freqLabel(f: Float): String {
        if (f < 1000f) {
            return if (abs(f - f.toInt()) < 0.05f) {
                f.toInt().toString()
            } else {
                String.format(Locale.US, "%.1f", f)
            }
        }
        val k = f / 1000f
        return String.format(Locale.US, "%.2f", k).trimEnd('0').trimEnd('.') + "k"
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
