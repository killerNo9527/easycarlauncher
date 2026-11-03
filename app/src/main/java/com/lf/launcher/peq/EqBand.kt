package com.lf.launcher.peq

/**
 * 频段滤波器类型。
 *
 * 对应 Poweramp Equalizer 里 `com.maxmpz.dsp.data.Band.type` 这个 int 字段所枚举的语义
 * （那边是 int，具体数值未从静态证据中取得，这里用 enum 表达同等能力集）。
 */
enum class BandType {
    /** 峰值（钟形），参量 EQ 最常用 */
    PEAK,

    /** 低架，抬/切低频整体 */
    LOW_SHELF,

    /** 高架 */
    HIGH_SHELF,

    /** 带通：只保留该频段，用于单频段监听/诊断 */
    BAND_PASS,

    /** 陷波：窄带抑制（消除某个共振峰） */
    NOTCH,

    /** 低通 / 高通：做频带限制 */
    LOW_PASS,
    HIGH_PASS;

    /** 该类滤波器是否使用 Q 参数 */
    val usesQ: Boolean
        get() = this !in setOf(LOW_SHELF, HIGH_SHELF)

    /** 该类滤波器是否使用增益参数（带通/陷波/高低通只改形状不改电平） */
    val usesGainDb: Boolean
        get() = this in setOf(PEAK, LOW_SHELF, HIGH_SHELF)
}

/**
 * 一个可自定义的频段。
 *
 * 字段与 Poweramp 的 `Band` 类一一对应：
 * `type` / `frequency` / `q` / `gain`(float) / `enabled` / `channels` / `name`(String)。
 *
 * @param freqHz  中心频率（或架式滤波器的转折频率），单位 Hz
 * @param q       品质因数，越大越窄；架式滤波器通常 0.5~1.0，峰值 0.3~10
 * @param gainDb  增益，单位 dB（仅 PEAK / LOW_SHELF / HIGH_SHELF 有效）
 * @param enabled 是否参与处理（对应 UI 上的"Band is locked/关闭"）
 * @param name    用户可读名称，便于预设序列化
 * @param channelMask 声道掩码；0 表示对所有声道生效
 */
data class EqBand(
    val type: BandType = BandType.PEAK,
    val freqHz: Float = 1000f,
    val q: Float = 1.41f,
    val gainDb: Float = 0f,
    val enabled: Boolean = true,
    val name: String = "",
    val channelMask: Int = 0,
) {
    init {
        require(freqHz > 0f) { "freqHz 必须 > 0" }
        require(q > 0f) { "q 必须 > 0" }
        require(gainDb in -48f..24f) { "gainDb 超出 ±48dB 合理范围" }
    }

    /** 该频段对某个声道的实际增益（未启用时恒为 0，等价于全通） */
    fun effectiveGainDb(channel: Int): Float {
        if (!enabled) return 0f
        if (channelMask != 0 && (channelMask and (1 shl channel)) == 0) return 0f
        return if (type.usesGainDb) gainDb else 0f
    }

    companion object {
        /** 参数化模式下的默认新频段（对应 UI 的 "Add Band"） */
        fun default(freqHz: Float) = EqBand(
            type = BandType.PEAK,
            freqHz = freqHz,
            q = 1.41f,
            gainDb = 0f,
            name = "",
        )
    }
}
