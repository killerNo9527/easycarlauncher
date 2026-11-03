package com.lf.launcher.eq

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.lf.launcher.R
import com.lf.launcher.peq.EqPresets
import com.lf.launcher.settings.Keys
import com.lf.launcher.settings.SettingsStore
import com.lf.launcher.ui.SystemUi

/**
 * 均衡器调整页。
 *
 * 31 段 1/3 倍频程，作用于**全局输出混音**（session 0），所以桌面上任何播放器出来的声音都会过它。
 * 生效链路见 [EqEngine]；本机不支持时页面会明说，不会假装开了。
 */
class EqActivity : AppCompatActivity() {

    private lateinit var editor: EqEditorView
    private lateinit var statusView: TextView
    private lateinit var toggleView: TextView
    private lateinit var preampView: TextView
    private lateinit var presetFlat: TextView
    private lateinit var presetCar: TextView
    private lateinit var presetCustom: TextView

    private var gains = FloatArray(EqPresets.BAND_COUNT)
    private var preset = PRESET_CUSTOM
    private var preampDb = 0f
    private var enabled = false

    private val ui = Handler(Looper.getMainLooper())
    private val applyTask = Runnable { pushToEngine() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 直接从 adb 拉起来时 LauncherApp 可能没跑过，这里补一次（init 内部有幂等判断）
        SettingsStore.init(this)
        setContentView(R.layout.activity_eq)

        // 和桌面同一套系统栏策略：默认沉浸式独占整屏；用户开了「使用系统状态栏」时
        // 这里就布局到栏下面再补 padding。不处理的话系统时钟会压在「均衡器」标题上。
        SystemUi.apply(this)
        SystemUi.attachContentInset(findViewById(R.id.eq_root), withCustomPadding = false)

        editor = findViewById(R.id.eq_editor)
        statusView = findViewById(R.id.eq_status)
        toggleView = findViewById(R.id.eq_toggle)
        preampView = findViewById(R.id.preamp_value)
        presetFlat = findViewById(R.id.preset_flat)
        presetCar = findViewById(R.id.preset_car)
        presetCustom = findViewById(R.id.preset_custom)

        load()

        editor.gains = gains
        // 注意：EqEditorView 内部的 gains 是 `value.copyOf()` 出来的**副本**（见它的 setter），
        // 拖动时改的是它自己那份。所以这里必须把回调带回来的值写回本 Activity 的数组，
        // 否则 scheduleApply() 下发的、onEditFinished 落盘的，都是拖之前那组旧增益 ——
        // 表现就是「推子拉满了、界面显示 12dB，但声音一点没变，重启后还回到 0」。
        editor.onGainChanged = { index, gainDb ->
            gains[index] = gainDb
            preset = PRESET_CUSTOM
            renderPresets()
            scheduleApply()
        }
        editor.onEditFinished = { persist() }
        editor.onRequestInput = { index -> showGainInput(index) }

        toggleView.setOnClickListener {
            enabled = !enabled
            persist()
            pushToEngine()
        }
        findViewById<TextView>(R.id.eq_close).setOnClickListener { finish() }
        findViewById<TextView>(R.id.eq_reset).setOnClickListener {
            gains = FloatArray(EqPresets.BAND_COUNT)
            preset = PRESET_FLAT
            editor.gains = gains
            renderPresets()
            persist()
            pushToEngine()
        }

        presetFlat.setOnClickListener { applyPreset(PRESET_FLAT, EqPresets.FLAT) }
        presetCar.setOnClickListener { applyPreset(PRESET_CAR, EqPresets.CAR_CORRECTION) }
        presetCustom.setOnClickListener {
            // 自定义 = 保留当前曲线，只是把来源标记回来（不发呆的按钮）
            preset = PRESET_CUSTOM
            persist()
            renderPresets()
        }

        findViewById<TextView>(R.id.preamp_minus).setOnClickListener { changePreamp(-1f) }
        findViewById<TextView>(R.id.preamp_plus).setOnClickListener { changePreamp(+1f) }

        renderAll()

        // 页面一进来就把当前配置推给 effect：既保证「开着」的状态真的在跑，
        // 也顺手探测本机支不支持 —— 不支持会立刻在状态栏说明，并把开关弹回去
        if (enabled) {
            pushToEngine()
        } else {
            EqEngine.probe()
            renderStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        // 从别的页面回来时 systemUiVisibility 可能被系统清掉，重来一次
        SystemUi.apply(this)
        if (enabled) pushToEngine()
    }

    override fun onDestroy() {
        ui.removeCallbacks(applyTask)
        super.onDestroy()
    }

    // ---------- 状态 ----------

    private fun renderAll() {
        renderToggle()
        renderPresets()
        renderPreamp()
        renderStatus()
    }

    private fun renderToggle() {
        toggleView.text = if (enabled) "已启用 · 点此关闭" else "点此启用"
        toggleView.isSelected = enabled
        toggleView.setTextColor(if (enabled) 0xFF35C6F4.toInt() else 0xFFC7CDD4.toInt())
    }

    private fun renderPresets() {
        presetFlat.isSelected = preset == PRESET_FLAT
        presetCar.isSelected = preset == PRESET_CAR
        presetCustom.isSelected = preset == PRESET_CUSTOM
    }

    private fun renderPreamp() {
        preampView.text = String.format(java.util.Locale.US, "前级 %+.1f dB", preampDb)
    }

    private fun renderStatus() {
        val text = when {
            !enabled -> "未开启"
            EqEngine.lastError != null -> EqEngine.lastError!!
            !EqEngine.created -> "未启用"
            else -> "全局生效中 · 31 段"
        }
        statusView.text = text
        statusView.setTextColor(
            if (enabled && EqEngine.lastError == null && EqEngine.created) 0xFF8FD9A8.toInt()
            else if (EqEngine.lastError != null) 0xFFE8734A.toInt()
            else 0xFF8A929E.toInt()
        )
    }

    // ---------- 动作 ----------

    private fun applyPreset(id: String, data: FloatArray) {
        gains = data.copyOf()
        preset = id
        editor.gains = gains
        renderPresets()
        persist()
        // 选预设通常意味着想听效果，顺手把开关打开（已经开着就只更新曲线）
        if (!enabled) {
            enabled = true
            renderToggle()
            persist()
        }
        pushToEngine()
    }

    private fun changePreamp(delta: Float) {
        preampDb = (preampDb + delta).coerceIn(-24f, 0f)
        renderPreamp()
        persist()
        pushToEngine()
    }

    /**
     * 双击某段后弹的数值输入框。
     *
     * 为什么不直接给 31 个 EditText：那是 31 个焦点和 31 份输入法，横屏车机上点起来很糟。
     * 双击哪根就给哪根输入，键盘弹出时只占屏幕中间一条。
     */
    private fun showGainInput(index: Int) {
        val freq = EqPresets.FREQS[index]
        val label = if (freq >= 1000f) {
            String.format(java.util.Locale.US, "%.1f kHz", freq / 1000f).replace(".0 ", " ")
        } else {
            String.format(java.util.Locale.US, "%.0f Hz", freq)
        }

        val input = EditText(this).apply {
            setText(String.format(java.util.Locale.US, "%.1f", gains[index]))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
            selectAll()
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setTextColor(0xFFF2F5F8.toInt())
            setHintTextColor(0xFF8A929E.toInt())
            hint = "±12.0"
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), dp(0))
            addView(input)
        }

        AlertDialog.Builder(this, R.style.EqDialogTheme)
            .setTitle("$label  增益 (dB)")
            .setView(box)
            .setPositiveButton("确定") { _, _ ->
                val v = input.text.toString().trim().toFloatOrNull()
                if (v == null) {
                    toast("请输入数字，例如 -2.5")
                    return@setPositiveButton
                }
                gains[index] = v.coerceIn(-MAX_DB, MAX_DB)
                preset = PRESET_CUSTOM
                editor.gains = gains
                renderPresets()
                persist()
                pushToEngine()
            }
            .setNeutralButton("归零") { _, _ ->
                gains[index] = 0f
                preset = PRESET_CUSTOM
                editor.gains = gains
                renderPresets()
                persist()
                pushToEngine()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun scheduleApply() {
        ui.removeCallbacks(applyTask)
        ui.postDelayed(applyTask, 120)
    }

    private fun pushToEngine() {
        ui.removeCallbacks(applyTask)
        val ok = EqEngine.apply(gains, enabled, preampDb)
        if (!ok && enabled) {
            // 本机建不出 effect：把开关退回去，别让用户以为在跑
            enabled = false
            SettingsStore.setBool(Keys.EQ_ENABLED, false)
        }
        renderToggle()
        renderStatus()
    }

    // ---------- 存取 ----------

    private fun load() {
        preset = EqSettings.preset()
        enabled = EqSettings.enabled()
        preampDb = EqSettings.preampDb()
        gains = EqSettings.gains()
    }

    private fun persist() {
        EqSettings.save(enabled, preset, preampDb, gains)
    }

    companion object {
        const val PRESET_FLAT = EqSettings.PRESET_FLAT
        const val PRESET_CAR = EqSettings.PRESET_CAR
        const val PRESET_CUSTOM = EqSettings.PRESET_CUSTOM

        /** 与 [EqEditorView] 的推子量程保持一致 */
        private const val MAX_DB = 12f
    }
}
