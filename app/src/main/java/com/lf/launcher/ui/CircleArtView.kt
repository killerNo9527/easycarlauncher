package com.lf.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * 圆形封面。
 *
 * 为什么不用 `ImageView + clipToOutline`：`clipToOutline` 取的是 background 的 outline，
 * 本工程的 `bg_album_cover` 是 `shape="oval"`，但实测在目标机器上并没有裁出圆——
 * 图还是方块（dumpsys / 截图都确认过）。
 *
 * 这里换成 BitmapShader + drawCircle：形状由 `drawCircle` 决定，**不依赖任何裁剪 API**，
 * 也就不受「某些设备 clipPath 失效」的影响；缩放按 centerCrop 算，圆外不留边。
 *
 * 没有图时什么都不画，直接露出 XML 里 `bg_album_cover` 的圆形渐变占位。
 */
class CircleArtView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        isDither = true
    }
    private val matrix = Matrix()

    private var art: Bitmap? = null
    private var shader: BitmapShader? = null

    fun setArt(bitmap: Bitmap?) {
        if (art === bitmap) return
        art = bitmap
        shader = bitmap?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        paint.shader = shader
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = art ?: return
        val s = shader ?: return
        val side = min(width, height).toFloat()
        if (side <= 0f) return

        // centerCrop：把短边缩放到底，长边居中裁掉
        val scale = max(side / bmp.width, side / bmp.height)
        val dx = (side - bmp.width * scale) / 2f
        val dy = (side - bmp.height * scale) / 2f
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)
        s.setLocalMatrix(matrix)

        canvas.drawCircle(width / 2f, height / 2f, side / 2f, paint)
    }
}
