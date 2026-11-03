package com.lf.launcher.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.lf.launcher.R

/**
 * 给高德悬浮窗补上卡片圆角。
 *
 * ## 为什么需要这一层
 *
 * 地图是高德自己建的 `TYPE_APPLICATION_OVERLAY` 窗口，矩形永远是方的。
 * 只作用于「自己 View 树」的手段（`clipToOutline`、`ViewOutlineProvider`、
 * `Window.setBackgroundDrawable`）都碰不到别人的窗口，所以只有两条路：
 *
 * 1. 让高德自己画圆角 —— 已实测无效：radius / corner / cornerRadius / corner_radius /
 *    round / roundRadius / rx / r 八个候选 extras 全发了一遍，`mAttrs` 和四角毫无变化，
 *    它只读 x / y / w / h。
 * 2. **在它上面再盖一层** —— 就是本类。
 *
 * ## 遮罩怎么画
 *
 * 遮罩是与卡片矩形等大的窗口，只负责「卡片形状以外」的那部分像素：
 *
 * ```
 * 四角楔形  ← 壁纸（卡片圆角外本该露出的东西）
 * 一圈细边  ← 卡片自己的 drawable（保留 18dp 圆角 + 1dp 描边）
 * 中间       ← 全透明（地图从下面透出来）
 * ```
 *
 * 两个关键点，都是踩过坑才定下来的：
 *
 * - **楔形必须填壁纸，不能填卡片色。** 卡片 drawable 自己是圆角的，圆角外面它根本不落笔；
 *   就算强行填成卡片底色，角上还是一个方块，只是变暗了，看着仍是直角。
 *   填壁纸才是「角被切掉」的观感。壁纸层是一张固定的全屏渐变 drawable，
 *   按 `(-left, -top, 屏宽-left, 屏高-top)` 偏移取样就能和卡片背后的真背景逐像素对上。
 * - **不能用 `canvas.clipPath(非凸路径)`。** 这台设备上路径裁剪之后什么都不剩
 *   （红底能画出来，裁剪后整块变空）。改成「画进位图再用 `PorterDuff.CLEAR` 挖洞」，
 *   逐像素运算，不依赖路径裁剪实现。
 *
 * ## 两个约束
 *
 * - 窗口必须 `FLAG_NOT_TOUCHABLE`，否则地图拖不动、缩放手势也会被吃掉；
 * - 跨应用同层级窗口的 z 序由系统按加入先后决定，高德的窗口可能比我们晚建，
 *   所以 [show] 会在 [REATTACH_DELAY] 后移除重加一次，把它重新压到上面。
 */
object MapMaskOverlay {

    private const val TAG = "MapMask"
    private const val REATTACH_DELAY = 450L

    /** addView 失败后的冷却时间：没权限的话重试再多次也挂不上 */
    private const val FAIL_RETRY_MS = 8_000L

    private val main = Handler(Looper.getMainLooper())
    private var host: CornerFrameView? = null
    private var pending: Runnable? = null
    private var lastRect: Rect? = null
    private var lastRadius = 0f

    /**
     * 上一次 addView 失败的时刻。
     *
     * 没有悬浮窗权限时 addView 必然抛 BadTokenException，而 [show] 会被布局变化反复调用，
     * 几何比对又只在「已挂上」时才有意义 —— 不设冷却就会变成每秒几十次的重试 + 几十行日志，
     * 日志缓冲被这一条刷满，别的日志（包括排查用的）全被挤掉。
     */
    private var failedAt = 0L
    private var failureLogged = false

    /**
     * 在 [rect]（卡片的屏幕绝对矩形）上盖一层圆角遮罩。
     *
     * @param radiusPx 圆角半径，直接给 `home_card_corner_radius` 的像素值
     */
    fun show(ctx: Context, rect: Rect, radiusPx: Float, background: List<View>) {
        if (rect.width() <= 0 || rect.height() <= 0) return
        // ctx 必须是 Activity 的 Context：固定日间/夜间时 uiMode 覆盖只挂在它身上，
        // 用 applicationContext 取 drawable 会拿到系统默认那一套颜色
        val app = ctx.applicationContext
        val themed = ctx
        val r = Rect(rect)

        // 几何没变就别重建：syncMapCard 会被布局变化反复触发，
        // 每次 remove/add 会让窗口闪，还会反过来触发新一轮 layout
        if (host != null && lastRect == r && lastRadius == radiusPx) return

        // 刚失败过（多半是没权限）就歇一会儿，别把 show 的高频调用放大成重试风暴
        if (failedAt != 0L && SystemClock.elapsedRealtime() - failedAt < FAIL_RETRY_MS) return

        detach(app)
        attach(app, themed, r, radiusPx, background)

        // 高德收到广播后才建窗口，可能晚于我们 → 延后再压一次
        pending?.let { main.removeCallbacks(it) }
        val again = Runnable {
            if (host != null) {
                detach(app)
                attach(app, themed, r, radiusPx, background)
            }
        }
        pending = again
        lastRect = r
        lastRadius = radiusPx
        // 没挂上就没有「压 z 序」这回事，别再排一次
        if (host != null) main.postDelayed(again, REATTACH_DELAY)
    }

    fun hide() {
        pending?.let { main.removeCallbacks(it) }
        pending = null
        lastRect = null
        lastRadius = 0f
        host?.context?.applicationContext?.let { detach(it) }
    }

    fun isShown(): Boolean = host != null

    // ------------------------------------------------------------------

    private fun attach(
        app: Context,
        themed: Context,
        rect: Rect,
        radiusPx: Float,
        background: List<View>,
    ) {
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val density = themed.resources.displayMetrics.density

        val v = CornerFrameView(
            app,
            rect,
            background,
            ContextCompat.getDrawable(themed, R.drawable.bg_map_placeholder)?.mutate(),
            ContextCompat.getDrawable(themed, R.drawable.bg_wallpaper_default)?.mutate(),
        ).apply {
            this.radiusPx = radiusPx
            this.strokePx = density.coerceAtLeast(1f)
        }
        val lp = WindowManager.LayoutParams(
            rect.width(),
            rect.height(),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = rect.left
            y = rect.top
            title = "MapCornerMask"
        }

        runCatching { wm.addView(v, lp) }
            .onSuccess {
                host = v
                failedAt = 0L
                failureLogged = false
                Log.i(TAG, "mask on ${rect.width()}x${rect.height()} @ (${rect.left},${rect.top}) r=$radiusPx")
            }
            .onFailure {
                failedAt = SystemClock.elapsedRealtime()
                // 同一种失败只记一次：这条日志以前一秒钟能刷几十行
                if (!failureLogged) {
                    failureLogged = true
                    Log.w(TAG, "mask 添加失败（多半是没有悬浮窗权限），${FAIL_RETRY_MS / 1000}s 内不再重试：$it")
                }
            }
    }

    private fun detach(app: Context) {
        val v = host ?: return
        host = null
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        runCatching { wm.removeViewImmediate(v) }
            .onFailure { Log.w(TAG, "mask 移除失败：$it") }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    /**
     * 只画「卡片以外」的像素，见类注释里的三段式。
     */
    private class CornerFrameView(
        ctx: Context,
        private val cardRect: Rect,
        private val backgroundLayers: List<View>,
        private val cardShape: Drawable?,
        private val wallpaper: Drawable?,
    ) : View(ctx) {

        var radiusPx = 0f
        var strokePx = 0f

        private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }

        private var layer: Bitmap? = null

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            layer?.recycle()
            layer = buildLayer(w, h)
        }

        private fun buildLayer(w: Int, h: Int): Bitmap? {
            if (w <= 0 || h <= 0) return null
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)

            // ① 四角：铺壁纸，再把卡片形状整块挖掉 —— 留下的是圆角外侧四个楔形。
            //    壁纸按卡片在屏幕上的位置反推 bounds，才能和卡片背后的真背景对齐
            // 直接把桌面的壁纸 View 画进来：它可能加载的是系统壁纸、还带 centerCrop，
            // 只有让 View 自己走一遍绘制，四角的颜色才和卡片外真的背景完全一致。
            // 退路：拿不到 View 时用默认渐变，按卡片位置偏移取样。
            if (backgroundLayers.isNotEmpty()) {
                // 背景不是「一张壁纸」，而是壁纸 + scrim 两层叠出来的，
                // 所以按原顺序逐层画，缺一层颜色就会偏亮
                val save = c.save()
                c.translate(-cardRect.left.toFloat(), -cardRect.top.toFloat())
                c.clipRect(cardRect)   // 只光栅化卡片范围，避免整屏重绘
                backgroundLayers.forEach { runCatching { it.draw(c) } }
                c.restoreToCount(save)
            } else {
                wallpaper?.let { wp ->
                    val dm = resources.displayMetrics
                    wp.setBounds(
                        -cardRect.left,
                        -cardRect.top,
                        dm.widthPixels - cardRect.left,
                        dm.heightPixels - cardRect.top,
                    )
                    wp.draw(c)
                }
            }
            c.drawRoundRect(
                RectF(0f, 0f, w.toFloat(), h.toFloat()),
                radiusPx,
                radiusPx,
                clearPaint,
            )

            // ② 一圈细边：卡片 drawable 只留下贴着边界的那条带（含圆弧上的描边）。
            //    内缩 strokePx 是为了不让 CLEAR 把描边本身也削掉
            val cs = cardShape
            if (cs != null) {
                val edge = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val ec = Canvas(edge)
                cs.setBounds(0, 0, w, h)
                cs.draw(ec)
                val s = strokePx
                val r = (radiusPx - s).coerceAtLeast(0f)
                ec.drawRoundRect(RectF(s, s, w - s, h - s), r, r, clearPaint)
                c.drawBitmap(edge, 0f, 0f, null)
                edge.recycle()
            }

            return bmp
        }

        override fun onDraw(canvas: Canvas) {
            val b = layer ?: buildLayer(width, height)?.also { layer = it } ?: return
            canvas.drawBitmap(b, 0f, 0f, null)
        }
    }
}
