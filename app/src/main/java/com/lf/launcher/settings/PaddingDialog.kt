package com.lf.launcher.settings

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.lf.launcher.R

/**
 * 「全局四边留白」调节器。
 *
 * 为什么用 SeekBar 而不是键盘输入：车机上打字最难用，而留白是个「边看边调」的量。
 * 拖动即时写入偏好，关掉设置回到桌面就能看到效果（桌面 onResume 会重算 padding）。
 *
 * 上限 96dp：再大基本就是「卡片被压扁」，没有实际意义。
 */
object PaddingDialog {

    private const val MAX_DP = 96

    /** 顺序固定：上 / 下 / 左 / 右 */
    private val SIDES = listOf(
        Keys.PAD_TOP to "上",
        Keys.PAD_BOTTOM to "下",
        Keys.PAD_START to "左",
        Keys.PAD_END to "右",
    )

    fun show(context: Context, onDismiss: () -> Unit = {}) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), dp(context, 8), dp(context, 24), dp(context, 8))
        }

        val bars = mutableListOf<SeekBar>()

        for ((key, label) in SIDES) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val name = TextView(context).apply {
                text = label
                textSize = 16f
                setTextColor(context.getColor(R.color.text_secondary))
            }
            row.addView(name, LinearLayout.LayoutParams(dp(context, 36), ViewGroup.LayoutParams.WRAP_CONTENT))

            val bar = SeekBar(context).apply {
                max = MAX_DP
                progress = SettingsStore.getInt(key, Defaults.PAD).coerceIn(0, MAX_DP)
            }
            row.addView(
                bar,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(context, 8)
                    marginEnd = dp(context, 8)
                },
            )

            val value = TextView(context).apply {
                textSize = 16f
                gravity = Gravity.END
                setTextColor(context.getColor(R.color.text_primary))
                text = "${bar.progress} dp"
            }
            row.addView(value, LinearLayout.LayoutParams(dp(context, 64), ViewGroup.LayoutParams.WRAP_CONTENT))

            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    value.text = "$progress dp"
                    // 拖动中就一直写：车机断电 / 界面被杀也不会丢，代价只是几次 apply()
                    SettingsStore.setInt(key, progress)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })

            bars += bar
            box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 48)))
        }

        val hint = TextView(context).apply {
            text = "在系统栏占位之外再补的边距。车机上系统栏高度经常报不准，用这里对；" +
                "调完回到桌面即可看到效果（只作用于桌面，设置页只避让系统栏）"
            textSize = 13f
            setTextColor(context.getColor(R.color.text_tertiary))
        }
        box.addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 「清零」放在对话框里面：用按钮栏的话点一下就会关窗，还得再开一次
        val reset = TextView(context).apply {
            text = "全部清零"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(context.getColor(R.color.accent))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 12), 0, dp(context, 4))
            setOnClickListener { bars.forEach { it.progress = 0 } }
        }
        box.addView(reset, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val dialog = AlertDialog.Builder(context, R.style.Theme_CarLauncher_Dialog)
            .setTitle("全局四边留白（dp）")
            .setView(box)
            .setPositiveButton("完成", null)
            .create()

        dialog.setOnDismissListener { onDismiss() }
        dialog.show()
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
