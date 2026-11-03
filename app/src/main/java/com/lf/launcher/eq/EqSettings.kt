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

    fun save(enabled: Boolean, preset: String, preampDb: Float, gains: FloatArray) {
        SettingsStore.setBool(Keys.EQ_ENABLED, enabled)
        SettingsStore.setString(Keys.EQ_PRESET, preset)
        SettingsStore.setString(Keys.EQ_PREAMP, preampDb.toString())
        SettingsStore.setString(Keys.EQ_GAINS, gains.joinToString(","))
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
        return EqEngine.apply(gains(), true, preampDb())
    }
}
