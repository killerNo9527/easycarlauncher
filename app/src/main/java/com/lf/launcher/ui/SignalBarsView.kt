package com.lf.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.lf.launcher.R

/**
 * 状态栏里的信号格：4 根从矮到高的圆角柱。
 *
 * 为什么自己画，不用 4 张矢量图做 level-list：
 *
 * 1. 4 档就是 4 个文件，改一次配色要改 4 处，而这里只要一句 `setSignal(level)`；
 * 2. 16~26dp 的小图上，矢量描边缩放后容易发虚，直接按像素画柱子反而干净；
 * 3. 未达到的档位要按"低透明度"画，用图片做就得每档再出两张（亮/暗），得不偿失。
 *
 * 尺寸不写死：全部按当前 View 的可用宽高算，所以三档屏幕 + 三档状态栏高度都不用改代码。
 */
class SignalBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = RectF()

    /** 有信号时的柱色（跟状态栏文字同一套，日夜各一份） */
    private val barColor = context.getColor(R.color.text_primary)

    /** 读不到信号时的柱色：比文字色浅一档，和 Wi-Fi / 蓝牙的「关」态同色 */
    private val faintColor = context.getColor(R.color.status_icon_faint)

    /** 「不可用」标记色：全状态栏唯一的红 */
    private val markColor = context.getColor(R.color.status_icon_mark)

    /** 0..4，与 `SignalStrength.getLevel()` 同义；0 = 无服务 */
    private var level = 0

    /** 有没有可用的蜂窝模块/权限。false 时整组柱子都画成"不可用"，不表达档位 */
    private var available = false

    init {
        paint.color = barColor
    }

    fun setSignal(level: Int, available: Boolean) {
        val l = level.coerceIn(0, MAX_LEVEL)
        if (l == this.level && available == this.available) return
        this.level = l
        this.available = available
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop.toFloat()
        val h = (height - paddingBottom).toFloat() - top
        val w = right - left
        if (w <= 0f || h <= 0f) return

        // 柱宽与缝：缝按总宽比例取，避免不同尺寸下"要么挤在一起要么散开"
        val gap = w * GAP_RATIO
        val barW = (w - gap * (MAX_LEVEL - 1)) / MAX_LEVEL
        if (barW <= 0f) return
        val radius = barW / 2f

        // 柱子不占满整个高度：旁边的 Wi-Fi / 蓝牙是描边字形，视觉高度只有 ~18dp，
        // 柱子顶到上下边会让它看起来比别处大一档。这里收一档并垂直居中。
        val bandH = h * BAR_BAND
        val bandBottom = top + (h + bandH) / 2f

        // 不可用时柱子一律走 FAINT_ALPHA（"看得见轮廓、读不出档位"）；
        // 可用时点亮的那几根满不透明，没到的那几根压到 IDLE_ALPHA
        val activeAlpha = paint.alpha
        val idleAlpha = (paint.alpha * (if (available) IDLE_ALPHA else FAINT_ALPHA)).toInt()
        paint.color = if (available) barColor else faintColor

        for (i in 0 until MAX_LEVEL) {
            val barH = bandH * HEIGHT_RATIO[i]
            val l = left + i * (barW + gap)
            bar.set(l, bandBottom - barH, l + barW, bandBottom)

            // 有服务但这一档还没到 → 暗色；完全没有服务 → 全部暗色且更淡
            val lit = available && i < level
            paint.alpha = if (lit) activeAlpha else idleAlpha
            canvas.drawRoundRect(bar, radius, radius, paint)
        }

        // 彻底读不到信号（没有蜂窝模块 / 没有权限）时画一个红叉，
        // 和 Wi-Fi、蓝牙的「关」态是同一个符号：红叉 = 这一路用不了。
        // 上一版这里是一道斜杠，和旁边的 Wi-Fi / 蓝牙斜杠撞脸，四格里三根斜杠分不清谁是谁。
        if (!available) {
            paint.color = markColor
            paint.alpha = 255
            paint.strokeWidth = barW * 0.95f
            paint.strokeCap = Paint.Cap.ROUND
            val inset = barW * 0.35f
            canvas.drawLine(
                left + inset,
                bandBottom - bandH + inset,
                left + w - inset,
                bandBottom - inset,
                paint,
            )
            canvas.drawLine(
                left + w - inset,
                bandBottom - bandH + inset,
                left + inset,
                bandBottom - inset,
                paint,
            )
            paint.strokeWidth = 0f
            paint.strokeCap = Paint.Cap.BUTT
        }
        paint.alpha = 255
    }

    private companion object {
        const val MAX_LEVEL = 4

        /** 4 根柱子的高度比例（相对可用高度），视觉上是均匀递增 */
        val HEIGHT_RATIO = floatArrayOf(0.34f, 0.55f, 0.77f, 1f)

        /** 柱间缝占可用宽度的比例 */
        const val GAP_RATIO = 0.20f

        /** 柱子组占可用高度的比例（剩下的上下均分留白） */
        const val BAR_BAND = 0.80f

        /** 不可用时柱子本身淡到「看得见轮廓但读不出档位」 */
        const val FAINT_ALPHA = 0.55f
        const val IDLE_ALPHA = 0.20f
    }
}
