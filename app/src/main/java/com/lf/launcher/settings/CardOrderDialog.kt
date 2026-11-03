package com.lf.launcher.settings

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.lf.launcher.R

/**
 * 「右栏卡片顺序」调节器：三行，各自带 ↑ / ↓。
 *
 * 为什么用对话框而不是单独一页：只有三项，翻一页再回来反而更慢。
 * 点一下立刻写盘并重建列表，关掉对话框回桌面就能看到新顺序（桌面 onResume 重排）。
 * 排序用按钮不用拖动 —— 和状态栏快捷应用同一套理由：车机上拖动容错太低。
 */
object CardOrderDialog {

    fun show(context: Context, onChanged: () -> Unit = {}) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 4), dp(context, 20), dp(context, 12))
        }

        val hint = TextView(context).apply {
            text = "从上到下就是桌面右栏的顺序；地图卡固定在左边，不参与"
            textSize = 13f
            setTextColor(context.getColor(R.color.text_tertiary))
            setPadding(0, 0, 0, dp(context, 10))
        }

        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        box.addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(rows, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        lateinit var refresh: () -> Unit
        refresh = {
            rows.removeAllViews()
            val order = CardOrder.current()
            order.forEachIndexed { index, key ->
                rows.addView(
                    row(
                        context,
                        CardOrder.label(key),
                        canUp = index > 0,
                        canDown = index < order.lastIndex,
                        onUp = {
                            CardOrder.move(key, -1)
                            refresh()
                            onChanged()
                        },
                        onDown = {
                            CardOrder.move(key, 1)
                            refresh()
                            onChanged()
                        },
                    )
                )
            }
        }
        refresh()

        AlertDialog.Builder(context)
            .setTitle("右栏卡片顺序")
            .setView(box)
            .setPositiveButton("完成", null)
            .show()
    }

    private fun row(
        context: Context,
        title: String,
        canUp: Boolean,
        canDown: Boolean,
        onUp: () -> Unit,
        onDown: () -> Unit,
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 4), 0, dp(context, 4))
        }

        val name = TextView(context).apply {
            text = title
            textSize = 16f
            setTextColor(context.getColor(R.color.text_primary))
        }
        row.addView(
            name,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        row.addView(button(context, "↑", canUp, onUp))
        row.addView(button(context, "↓", canDown, onDown).apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(context, 8)
        })
        return row
    }

    private fun button(context: Context, glyph: String, enabled: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = glyph
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(context.getColor(R.color.text_secondary))
            background = context.getDrawable(R.drawable.bg_app_focus)
            // 到顶 / 到底时压暗且不吃点击：点了没反应比"按钮是灰的"更让人困惑
            alpha = if (enabled) 1f else 0.3f
            isClickable = enabled
            isFocusable = enabled
            if (enabled) setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(context, 44), dp(context, 44))
        }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
