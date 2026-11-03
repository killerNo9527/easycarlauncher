package com.lf.launcher.eq

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.lf.launcher.peq.BandType
import com.lf.launcher.peq.Biquad
import com.lf.launcher.peq.EqPresets
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * 均衡器编辑区：上半部频响曲线，下半部 31 段竖推子（可直接拖）。
 *
 * 为什么不复用 `EqCurve.curve()`：它对每个采样点都会把 31 个频段的 biquad 系数重算一遍
 * （128 × 31 次 sin/cos/pow），拖动时明显掉帧。这里每帧只算 31 次系数，采样点复用，
 * 拖动是跟手的。
 */
class EqEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    companion object {
        private const val SAMPLE_RATE = 48000
        private const val MAX_DB = 12f          // 推子可调范围 ±12dB
        private const val CURVE_RATIO = 0.58f   // 曲线区占高度比例
        private const val CURVE_POINTS = 192

        /** 单次点按的时限与双击的间隔上限 */
        private const val TAP_TIMEOUT_MS = 220L
        private const val DOUBLE_TAP_MS = 340L

        private val BG = Color.parseColor("#12151A")
        private val GRID = Color.parseColor("#252A32")
        private val GRID_STRONG = Color.parseColor("#39414D")
        private val CURVE = Color.parseColor("#35C6F4")
        private val CURVE_FILL = Color.parseColor("#2235C6F4")
        private val BAR_TRACK = Color.parseColor("#2A2F38")
        private val BAR_POS = Color.parseColor("#3FA9F5")
        private val BAR_NEG = Color.parseColor("#E8734A")
        private val LABEL = Color.parseColor("#8A929E")
        private val ZERO_LINE = Color.parseColor("#4A5361")
    }

    /** 31 段增益（dB），与 [EqPresets.FREQS] 对齐 */
    var gains: FloatArray = FloatArray(EqPresets.BAND_COUNT)
        set(value) {
            field = value.copyOf()
            rebuild()
            invalidate()
        }

    /** 拖动过程中回调（索引, 增益 dB） */
    var onGainChanged: ((index: Int, gainDb: Float) -> Unit)? = null

    /** 拖动结束（抬手）时回调，用来落盘 */
    var onEditFinished: (() -> Unit)? = null

    /**
     * 双击某一段时回调，让外面弹「输入数值」。
     *
     * 为什么需要它：31 段挤在 1080px 里，一格只有 30 多像素，±12dB 量程下 1px ≈ 0.1dB，
     * 想精确调 -2.5 这种值靠拖根本对不准。双击给个键盘输入口子是最省事的补救。
     */
    var onRequestInput: ((index: Int) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LABEL
        textSize = 11f * resources.displayMetrics.scaledDensity
    }
    private val curvePath = Path()
    private val fillPath = Path()
    private val barRect = RectF()
    private var curveDb = FloatArray(CURVE_POINTS)

    private var curveBottom = 0f
    private var dragIndex = -1
    private var barLeft = 0f
    private var barRegionTop = 0f
    private var barRegionHeight = 0f
    private var slotWidth = 0f
    private var centerY = 0f
    private var halfHeight = 0f

    // 双击识别：不用 GestureDetector，因为它会和拖动抢事件；这里自己判「短按 + 位移小于 slop」。
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var gainBeforeTouch = 0f
    private var lastTapIndex = -1
    private var lastTapAt = 0L

    init {
        setBackgroundColor(BG)
        rebuild()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val padH = 16f * resources.displayMetrics.density
        barLeft = padH
        slotWidth = (w - padH * 2) / EqPresets.BAND_COUNT
        curveBottom = h * CURVE_RATIO
        barRegionTop = curveBottom + 6f * resources.displayMetrics.density
        barRegionHeight = h - barRegionTop - 8f * resources.displayMetrics.density
        centerY = barRegionTop + barRegionHeight / 2f
        halfHeight = barRegionHeight / 2f - 6f * resources.displayMetrics.density
        rebuild()
    }

    /** 只重算系数与曲线，不触发回调 */
    private fun rebuild() {
        val coefs = ArrayList<Biquad>(EqPresets.BAND_COUNT)
        for (i in 0 until EqPresets.BAND_COUNT) {
            val g = gains.getOrElse(i) { 0f }
            if (abs(g) < 0.02f) continue
            coefs += Biquad.design(BandType.PEAK, SAMPLE_RATE, EqPresets.FREQS[i], 4.33f, g)
        }
        val lo = log10(20.0)
        val hi = log10(20000.0)
        for (k in 0 until CURVE_POINTS) {
            val t = k.toDouble() / (CURVE_POINTS - 1)
            val f = 10.0.pow(lo + (hi - lo) * t).toFloat()
            var db = 0f
            for (c in coefs) db += c.magnitudeDb(SAMPLE_RATE, f)
            curveDb[k] = db
        }
    }

    override fun onDraw(canvas: Canvas) {
        drawCurveArea(canvas)
        drawBars(canvas)
    }

    private fun drawCurveArea(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val padH = 16f * density
        val top = 12f * density
        val bottom = curveBottom - 18f * density
        val left = padH
        val right = width - padH
        if (bottom <= top) return

        // 纵轴自适应：目标曲线通常只有 ±2~3dB，固定 ±12 会把它压成一条直线。
        // 下限 6dB（不然一点小起伏会被放大成噪声），上限仍是推子量程。
        var peak = 0f
        for (v in curveDb) peak = maxOf(peak, abs(v))
        val range = maxOf(6f, minOf(MAX_DB, peak * 1.25f))
        val midY = (top + bottom) / 2f
        val halfH = (bottom - top) / 2f

        fun yOf(db: Float) = midY - (db / range) * halfH

        // 横向网格：0 / ±半程 / ±满程
        paint.strokeWidth = 1f
        for (db in listOf(range, range / 2f, 0f, -range / 2f, -range)) {
            paint.color = if (db == 0f) ZERO_LINE else GRID
            canvas.drawLine(left, yOf(db), right, yOf(db), paint)
        }
        // 量程标注
        textPaint.color = LABEL
        canvas.drawText(String.format(java.util.Locale.US, "+%.0f", range), left + 4f, yOf(range) + 12f * density, textPaint)
        canvas.drawText(String.format(java.util.Locale.US, "-%.0f", range), left + 4f, yOf(-range) - 4f * density, textPaint)

        // 纵向网格 + 频率标签（取整倍频程）
        val marks = listOf(31.5f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        val lo = log10(20.0)
        val hi = log10(20000.0)
        paint.color = GRID
        for (f in marks) {
            val t = ((log10(f.toDouble()) - lo) / (hi - lo)).toFloat()
            val x = left + (right - left) * t
            canvas.drawLine(x, top, x, bottom, paint)
        }

        // 曲线
        curvePath.reset()
        fillPath.reset()
        val stepX = (right - left) / (CURVE_POINTS - 1)
        for (k in 0 until CURVE_POINTS) {
            val x = left + stepX * k
            val y = yOf(curveDb[k].coerceIn(-range, range))
            if (k == 0) {
                curvePath.moveTo(x, y)
                fillPath.moveTo(x, yOf(0f))
                fillPath.lineTo(x, y)
            } else {
                curvePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(right, yOf(0f))
        fillPath.close()

        paint.style = Paint.Style.FILL
        paint.color = CURVE_FILL
        canvas.drawPath(fillPath, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.2f * density
        paint.color = CURVE
        paint.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(curvePath, paint)
        paint.style = Paint.Style.FILL

        // 频率标签画在曲线区下沿，和推子区留一点呼吸
        textPaint.color = LABEL
        for (f in marks) {
            val t = ((log10(f.toDouble()) - lo) / (hi - lo)).toFloat()
            val x = left + (right - left) * t
            val label = if (f >= 1000f) "${(f / 1000f).toInt()}k" else "${f.toInt()}"
            canvas.drawText(label, x - textPaint.measureText(label) / 2f, bottom + 13f * density, textPaint)
        }
    }

    private fun drawBars(canvas: Canvas) {
        if (slotWidth <= 0f) return
        val density = resources.displayMetrics.density
        val barW = slotWidth * 0.42f
        val radius = 2f * density

        paint.style = Paint.Style.FILL
        for (i in 0 until EqPresets.BAND_COUNT) {
            val cx = barLeft + slotWidth * (i + 0.5f)
            val left = cx - barW / 2f
            // 轨道
            barRect.set(left, centerY - halfHeight, left + barW, centerY + halfHeight)
            paint.color = BAR_TRACK
            canvas.drawRoundRect(barRect, radius, radius, paint)

            val g = gains.getOrElse(i) { 0f }.coerceIn(-MAX_DB, MAX_DB)
            val h = abs(g) / MAX_DB * halfHeight
            if (h > 0.5f) {
                paint.color = if (g >= 0f) BAR_POS else BAR_NEG
                if (g >= 0f) {
                    barRect.set(left, centerY - h, left + barW, centerY)
                } else {
                    barRect.set(left, centerY, left + barW, centerY + h)
                }
                canvas.drawRoundRect(barRect, radius, radius, paint)
            }
            // 当前拖动段的描边
            if (i == dragIndex) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.4f * density
                paint.color = Color.WHITE
                barRect.set(left - 1.5f, centerY - halfHeight - 1.5f, left + barW + 1.5f, centerY + halfHeight + 1.5f)
                canvas.drawRoundRect(barRect, radius, radius, paint)
                paint.style = Paint.Style.FILL
            }
        }
        // 0dB 基准线
        paint.strokeWidth = 1f * density
        paint.color = ZERO_LINE
        canvas.drawLine(barLeft, centerY, width - barLeft, centerY, paint)
        paint.strokeWidth = 1f

        // 拖动时的数值气泡：31 段挤在一起，±2dB 只有十几像素，
        // 光看柱子读不出数，必须给一个数字回显。
        if (dragIndex in 0 until EqPresets.BAND_COUNT) {
            drawDragBubble(canvas, dragIndex)
        }
    }

    private fun drawDragBubble(canvas: Canvas, index: Int) {
        val density = resources.displayMetrics.density
        val f = EqPresets.FREQS[index]
        val fLabel = if (f >= 1000f) String.format(java.util.Locale.US, "%.0fk", f / 1000f) else "${f.toInt()}"
        val g = gains.getOrElse(index) { 0f }
        val label = String.format(java.util.Locale.US, "%sHz  %+.1f dB", fLabel, g)

        textPaint.color = Color.WHITE
        val tw = textPaint.measureText(label)
        val padX = 8f * density
        val padY = 5f * density
        val boxW = tw + padX * 2
        val boxH = textPaint.textSize + padY * 2

        val cx = barLeft + slotWidth * (index + 0.5f)
        val boxLeft = (cx - boxW / 2f).coerceIn(2f * density, width - boxW - 2f * density)
        val boxTop = barRegionTop - boxH - 2f * density

        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#E6182028")
        canvas.drawRoundRect(
            boxLeft, boxTop, boxLeft + boxW, boxTop + boxH,
            6f * density, 6f * density, paint,
        )
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = Color.parseColor("#4A5361")
        canvas.drawRoundRect(
            boxLeft, boxTop, boxLeft + boxW, boxTop + boxH,
            6f * density, 6f * density, paint,
        )
        paint.style = Paint.Style.FILL

        canvas.drawText(
            label,
            boxLeft + padX,
            boxTop + padY - textPaint.fontMetrics.ascent,
            textPaint,
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (slotWidth <= 0f) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.y < curveBottom - 6f * resources.displayMetrics.density) return false
                downX = event.x
                downY = event.y
                downAt = event.eventTime
                dragIndex = indexAt(event.x)
                gainBeforeTouch = gains[dragIndex]
                setGainFromY(event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.y < curveBottom - 6f * resources.displayMetrics.density) return false
                if (dragIndex < 0) return false
                setGainFromY(event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragIndex < 0) return false
                val idx = dragIndex
                dragIndex = -1
                val tapped = event.eventTime - downAt < TAP_TIMEOUT_MS &&
                    abs(event.x - downX) < touchSlop &&
                    abs(event.y - downY) < touchSlop
                if (tapped) {
                    // 双击 = 想精确输入，把刚才那一下「按触点定值」的副作用撤回，
                    // 否则弹出输入框的同时曲线已经被拖走一截
                    val prev = lastTapIndex
                    val prevAt = lastTapAt
                    if (prev == idx && event.eventTime - prevAt < DOUBLE_TAP_MS) {
                        gains[idx] = gainBeforeTouch
                        rebuild()
                        invalidate()
                        lastTapIndex = -1
                        lastTapAt = 0L
                        dragIndex = -1
                        onRequestInput?.invoke(idx)
                        return true
                    }
                    lastTapIndex = idx
                    lastTapAt = event.eventTime
                }
                invalidate()
                onEditFinished?.invoke()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragIndex >= 0) {
                    dragIndex = -1
                    invalidate()
                    onEditFinished?.invoke()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun indexAt(x: Float): Int =
        ((x - barLeft) / slotWidth).toInt().coerceIn(0, EqPresets.BAND_COUNT - 1)

    private fun setGainFromY(y: Float) {
        if (dragIndex < 0) return
        val db = ((centerY - y) / halfHeight * MAX_DB).coerceIn(-MAX_DB, MAX_DB)
        // 0.1dB 精度，避免拖动时末位乱跳
        gains[dragIndex] = (db * 10f).toInt() / 10f
        rebuild()
        invalidate()
        onGainChanged?.invoke(dragIndex, gains[dragIndex])
    }
}
