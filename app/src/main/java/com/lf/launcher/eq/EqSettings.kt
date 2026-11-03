package com.lf.launcher.eq

import com.lf.launcher.peq.EqPresets
import com.lf.launcher.settings.Keys
import com.lf.launcher.settings.SettingsStore

/**
 * 均衡器配置的存取。
 *
 * 单独抽出来是因为有两个入口要用它：调整页（[EqActivity]）和桌面（[LauncherActivity]
 * 每次回到前台都要把均衡器重新挂上 —— 车机把桌面进程杀掉重启后 effect 会丢）。
 */
object EqSettings {

    const val PRESET_FLAT = "flat"
    const val PRESET_CAR = "car"
    const val PRESET_CUSTOM = "custom"

    fun enabled(): Boolean = SettingsStore.getBool(Keys.EQ_ENABLED, false)

    fun preset(): String = SettingsStore.getString(Keys.EQ_PRESET, PRESET_CUSTOM)

    fun preampDb(): Float = SettingsStore.getString(Keys.EQ_PREAMP, "0").toFloatOrNull() ?: 0f

    /**
     * 31 个中心频率；没存过或存坏了就回落到 1/3 倍频程默认布局。
     *
     * 频点可以随增益一起改（界面上点频点数就能改 Hz），所以必须和增益成对存、成对读 ——
     * 只迁一个会出现"曲线是新的、作用位置还是旧的"这种对不上的状态。
     */
    fun freqs(): FloatArray {
        val raw = SettingsStore.getString(Keys.EQ_FREQS, "")
        if (raw.isNotBlank()) {
            val parts = raw.split(',')
            if (parts.size == EqPresets.BAND_COUNT) {
                val out = FloatArray(parts.size)
                var ok = true
                for (i in parts.indices) {
                    val v = parts[i].trim().toFloatOrNull()
                    if (v == null) { ok = false; break }
                    out[i] = v
                }
                if (ok) return EqPresets.sanitizeFreqs(out)
            }
        }
        return EqPresets.FREQS.copyOf()
    }

    /** 31 个增益；没存过就按预设给（默认平直） */
    fun gains(): FloatArray {
        val raw = SettingsStore.getString(Keys.EQ_GAINS, "")
        if (raw.isNotBlank()) {
            val parts = raw.split(',')
            if (parts.size == EqPresets.BAND_COUNT) {
                val out = FloatArray(parts.size)
                var ok = true
                for (i in parts.indices) {
                    val v = parts[i].trim().toFloatOrNull()
                    if (v == null) { ok = false; break }
                    out[i] = v
                }
                if (ok) return out
            }
        }
        return when (preset()) {
            PRESET_CAR -> EqPresets.CAR_CORRECTION.copyOf()
            PRESET_FLAT -> EqPresets.FLAT.copyOf()
            else -> EqPresets.FLAT.copyOf()
        }
    }

    /**
     * 落盘。**频点必须显式传**：早先给过 `freqs` 默认值，结果「建 effect 失败退回关闭」
     * 那条路径调 save 时用默认值把用户调的频点抹回了网格。靠签名强制杜绝。
     */
    fun save(
        enabled: Boolean,
        preset: String,
        preampDb: Float,
        gains: FloatArray,
        freqs: FloatArray,
    ) {
        SettingsStore.setBool(Keys.EQ_ENABLED, enabled)
        SettingsStore.setString(Keys.EQ_PRESET, preset)
        SettingsStore.setString(Keys.EQ_PREAMP, preampDb.toString())
        SettingsStore.setString(Keys.EQ_GAINS, gains.joinToString(","))
        SettingsStore.setString(Keys.EQ_FREQS, EqPresets.sanitizeFreqs(freqs).joinToString(","))
        EqEngine.remember(gains)
    }

    fun presetLabel(id: String = preset()): String = when (id) {
        PRESET_FLAT -> "平直"
        PRESET_CAR -> "车机矫正"
        else -> "自定义"
    }

    /**
     * 按存下来的配置把 effect 挂上（关着就确保它是关的）。
     *
     * @return false 表示本机建不出 effect，UI 应该提示
     */
    fun restore(): Boolean {
        if (!enabled()) {
            if (EqEngine.active) EqEngine.setEnabled(false)
            return true
        }
        return EqEngine.apply(gains(), true, preampDb(), freqs())
    }
}
