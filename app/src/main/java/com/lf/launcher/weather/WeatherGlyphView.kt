package com.lf.launcher.weather

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.lf.launcher.R

/**
 * 天气卡右侧的气象图标。
 *
 * ## 为什么是画出来的，不是图片
 *
 * 这套卡片里没有任何图标资产，忽然塞一组彩色小图会打断整列的黑白灰（见
 * [com.lf.launcher.R.layout.include_weather_card] 的老注释）。所以这里用纯线条画：
 * 单色、圆头描边、24×24 的设计网格按控件尺寸等比缩放，任何分辨率都清晰，
 * 颜色直接跟着主题的 `text_secondary` 走，日夜切换不用另准备一套图。
 *
 * ## 关于形状
 *
 * 云朵是几个圆的并集（[Path.op]），再补一条平底；月亮是大小两圆的差集（月牙）；
 * 太阳是实心圆 + 八根短射线。这些小图形在 56dp 上就能认出来，不需要细节。
 */
class WeatherGlyphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    enum class Kind {
        NONE, SUN, MOON, CLOUD_SUN, CLOUD_MOON, CLOUD, RAIN, HEAVY_RAIN, SNOW, THUNDER, FOG,
    }

    var kind: Kind = Kind.NONE
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 描边画云雨雪，填充画日月芯 —— 同一个色，靠明度差分层 */
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** 24 单位设计网格 → 像素的换算 */
    private var unit = 1f

    init {
        val main = ContextCompat.getColor(context, R.color.text_secondary)
        stroke.color = main
        // 日月芯用同色低透明：不引入第二个颜色，又能让图形有重量
        fill.color = Color.argb(60, Color.red(main), Color.green(main), Color.blue(main))
    }

    override fun onDraw(canvas: Canvas) {
        if (kind == Kind.NONE) return
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return
        unit = size / GRID
        canvas.save()
        canvas.translate((width - size) / 2f, (height - size) / 2f)
        canvas.scale(unit, unit)
        // 线宽也按设计单位给：缩放后看起来才是同一个粗细
        stroke.strokeWidth = 1.55f

        when (kind) {
            Kind.SUN -> drawSun(canvas, 12f, 12f, 4.2f, 8)
            Kind.MOON -> drawMoon(canvas, 12f, 12f, 6.2f)
            Kind.CLOUD -> drawCloud(canvas)
            Kind.CLOUD_SUN -> {
                // 只画偏上那几根射线：朝右下的会穿进云里，看着像画错了
                drawSun(canvas, 8.0f, 7.8f, 2.9f, 8, skipFromDeg = 10f, skipToDeg = 110f)
                drawCloud(canvas, dx = 2.8f, dy = 3.4f, scale = 0.84f)
            }
            Kind.CLOUD_MOON -> {
                drawMoon(canvas, 7.8f, 8.2f, 4.4f)
                drawCloud(canvas, dx = 2.8f, dy = 3.4f, scale = 0.84f)
            }
            Kind.RAIN -> {
                drawCloud(canvas, dy = -1.6f)
                for (x in floatArrayOf(9.6f, 13.4f, 17.2f)) {
                    canvas.drawLine(x, 18.6f, x - 1.3f, 21.6f, stroke)
                }
            }
            Kind.HEAVY_RAIN -> {
                drawCloud(canvas, dy = -2.0f)
                for (x in floatArrayOf(8.4f, 12.0f, 15.6f, 19.0f)) {
                    canvas.drawLine(x, 18.2f, x - 1.8f, 22.4f, stroke)
                }
            }
            Kind.SNOW -> {
                drawCloud(canvas, dy = -1.8f)
                for (x in floatArrayOf(9.8f, 13.6f, 17.4f)) {
                    canvas.drawLine(x - 0.9f, 19.2f, x + 0.9f, 21.0f, stroke)
                    canvas.drawLine(x + 0.9f, 19.2f, x - 0.9f, 21.0f, stroke)
                }
            }
            Kind.THUNDER -> {
                drawCloud(canvas, dy = -2.0f)
                val bolt = Path().apply {
                    moveTo(14.4f, 17.6f)
                    lineTo(11.2f, 21.4f)
                    lineTo(13.6f, 21.4f)
                    lineTo(12.0f, 24.2f)
                }
                canvas.drawPath(bolt, stroke)
            }
            // 雾/霾：三条波浪线。直着画会被认成「菜单」图标（第一次就是这么错的）
            Kind.FOG -> {
                drawWave(canvas, y = 8.2f, x0 = 4.4f, x1 = 19.6f)
                drawWave(canvas, y = 12.6f, x0 = 6.6f, x1 = 17.4f)
                drawWave(canvas, y = 17.0f, x0 = 4.4f, x1 = 15.4f)
            }
            Kind.NONE -> Unit
        }
        canvas.restore()
    }

    /** 一条波浪线：两个波峰，读起来是「雾气」而不是「三横线」 */
    private fun drawWave(canvas: Canvas, y: Float, x0: Float, x1: Float) {
        val path = Path()
        val w = x1 - x0
        val seg = w / 2f
        path.moveTo(x0, y)
        path.quadTo(x0 + seg * 0.5f, y - 1.9f, x0 + seg, y)
        path.quadTo(x0 + seg * 1.5f, y + 1.9f, x1, y)
        canvas.drawPath(path, stroke)
    }

    /**
     * 实心圆 + [rays] 根短射线。
     *
     * [skipFromDeg]~[skipToDeg] 这个扇区的射线不画（0° 朝右、90° 朝下）。
     * CLOUD_SUN 用它把扎进云朵的那几根去掉 —— 否则太阳的刺穿在云上，看着像画糊了。
     */
    private fun drawSun(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        rays: Int,
        skipFromDeg: Float = 0f,
        skipToDeg: Float = 0f,
    ) {
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, stroke)
        val inner = r + 2.2f
        val outer = r + 4.4f
        val step = (Math.PI * 2 / rays).toFloat()
        for (i in 0 until rays) {
            val deg = (360f / rays) * i
            if (skipToDeg > skipFromDeg && deg > skipFromDeg && deg < skipToDeg) continue
            val a = step * i
            val cos = Math.cos(a.toDouble()).toFloat()
            val sin = Math.sin(a.toDouble()).toFloat()
            canvas.drawLine(cx + cos * inner, cy + sin * inner, cx + cos * outer, cy + sin * outer, stroke)
        }
    }

    /** 月牙：大圆减小圆 */
    private fun drawMoon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val outer = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
        val bite = Path().apply { addCircle(cx + r * 0.62f, cy - r * 0.52f, r * 0.9f, Path.Direction.CW) }
        val crescent = Path().apply { op(outer, bite, Path.Op.DIFFERENCE) }
        canvas.drawPath(crescent, fill)
        canvas.drawPath(crescent, stroke)
    }

    /**
     * 云朵：三个圆的并集 + 一条平底。
     *
     * [dx]/[dy] 整体平移、[scale] 整体缩放 —— 「多云」「阴间多云」要在同一格里塞日/月 + 云，
     * 云得缩一点让位。
     */
    private fun drawCloud(canvas: Canvas, dx: Float = 0f, dy: Float = 0f, scale: Float = 1f) {
        val p = Path()
        val left = Path().apply { addCircle(8.6f, 13.2f, 3.5f, Path.Direction.CW) }
        val top = Path().apply { addCircle(13.2f, 11.2f, 4.4f, Path.Direction.CW) }
        val right = Path().apply { addCircle(17.2f, 13.6f, 2.9f, Path.Direction.CW) }
        p.op(left, top, Path.Op.UNION)
        p.op(p, right, Path.Op.UNION)
        // 平底：把三个圆的下沿连成一条直线，否则云底是三个鼓包
        val base = Path().apply { addRect(8.6f, 12.0f, 17.2f, 16.6f, Path.Direction.CW) }
        p.op(p, base, Path.Op.UNION)

        val m = Matrix()
        m.postScale(scale, scale)
        m.postTranslate(dx, dy)
        p.transform(m)
        canvas.drawPath(p, stroke)
    }

    private companion object {
        const val GRID = 24f
    }
}

/**
 * 和风的图标编码 → 图形。
 *
 * 编码表（v7）：100/150 晴，101~103 / 151~153 多云系，104 阴，3xx 雨（302~304 雷），
 * 4xx 雪，5xx 雾霾沙尘。拿不到编码时退化成按中文描述猜测，老缓存和其它数据源也能画出来。
 */
fun glyphFor(code: String?, text: String?): WeatherGlyphView.Kind {
    val c = code?.trim()?.toIntOrNull()
    if (c != null && c > 0) {
        return when {
            c == 150 -> WeatherGlyphView.Kind.MOON
            c == 100 -> WeatherGlyphView.Kind.SUN
            c in 151..153 -> WeatherGlyphView.Kind.CLOUD_MOON
            c in 101..103 -> WeatherGlyphView.Kind.CLOUD_SUN
            c == 104 -> WeatherGlyphView.Kind.CLOUD
            c in 302..304 -> WeatherGlyphView.Kind.THUNDER
            c in 307..399 -> WeatherGlyphView.Kind.HEAVY_RAIN
            c in 300..306 -> WeatherGlyphView.Kind.RAIN
            c in 400..499 -> WeatherGlyphView.Kind.SNOW
            c in 500..515 -> WeatherGlyphView.Kind.FOG
            else -> WeatherGlyphView.Kind.CLOUD
        }
    }
    val t = text.orEmpty()
    return when {
        t.isEmpty() -> WeatherGlyphView.Kind.NONE
        t.contains("雷") -> WeatherGlyphView.Kind.THUNDER
        t.contains("雪") || t.contains("冰雹") -> WeatherGlyphView.Kind.SNOW
        t.contains("雨") -> WeatherGlyphView.Kind.RAIN
        t.contains("雾") || t.contains("霾") || t.contains("沙") || t.contains("尘") ->
            WeatherGlyphView.Kind.FOG
        t.contains("阴") -> WeatherGlyphView.Kind.CLOUD
        t.contains("多云") || t.contains("少云") || t.contains("间晴") ->
            WeatherGlyphView.Kind.CLOUD_SUN
        t.contains("晴") -> WeatherGlyphView.Kind.SUN
        else -> WeatherGlyphView.Kind.CLOUD
    }
}
