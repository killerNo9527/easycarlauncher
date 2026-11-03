package com.lf.launcher.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.Keyframe
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Canvas
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.max

/**
 * 单行、超宽就横向扫一遍的文本（扫到句尾停住，不回开头）。
 *
 * 为什么不用 `TextView + ellipsize="marquee"`：
 *
 * 1. 系统 marquee 只在 `isSelected == true`（或获焦）时跑，而我们是按句整块替换文本的，
 *    被别的控件抢焦点 / 重新 attach 之后经常就静止了，而且没有任何回调能知道它停了；
 * 2. 它的滚动是无停顿循环：滚到底跳回开头接着滚，长句读起来是断的。
 *
 * 这里自己做：放得下就老老实实居中不动（大多数歌词落在这一档，视觉上跟普通 TextView 一样），
 * 放不下才横向扫，扫到句尾就停在那。
 *
 * **溢出时是自己 `canvas.drawText` 画，不走 TextView 的排版** —— 这一条是踩坑踩出来的：
 * 之前用 `super.onDraw` + `canvas.translate`，在车机上表现为「句子中间被切掉一段、右边留白」。
 * 原因是那样画什么完全取决于 TextView 内部的 Layout：`getLayout().width` 是不是
 * `VERY_WIDE`、ellipsize 有没有被别的调用改回来、gravity 有没有生效、以及它自己的裁剪范围 ——
 * 任何一处在不同 ROM 上不一样，窗口就落在错误的位置。
 * 自己画之后「测量」和「绘制」用的是同一个 Paint：`paint.measureText` 量多宽就画多宽，
 * 平移多少就切掉多少，不存在对不上的可能。
 */
class ScrollTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    /** 当前横向平移量（px，向左为正） */
    private var offset = 0f

    /** 需要滚动的最大距离；0 表示不需要滚（放得下） */
    private var travel = 0f

    /** 当前要画的文本；溢出时自己 drawText 用它，和 paint.measureText 是同一份 */
    private var drawnText: String = ""

    private var animator: ValueAnimator? = null

    /** 上一次判定用的「文本 | 可用宽度 | 字号」；没变就不再重启动画（否则会把进度吃掉） */
    private var lastKey: String = ""

    /** 判定过该滚，但当时暂停 / 不可见 / 没 attach，等条件满足再启动 */
    private var pendingStart = false

    /** 只在 debuggable 包里打滚动轨迹，正式包不输出 */
    private val debugLog =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** 暂停时不滚（暂停播放还让字在动，看着像没暂停） */
    private var paused = false

    /**
     * 这一句大概还能显示多久（ms），由调用方按歌词时间轴算好传进来；0 = 不知道。
     *
     * 为什么需要：一句歌词只停留三四秒，如果扫描时长是固定的（按速度算），长句
     * 常常**没扫到尾巴就换句了** —— 用户看到的就是「滚动只在能显示的那部分里晃」。
     * 有了预算就能把「扫完」安排在换句之前。
     */
    private var budgetMs = 0L

    init {
        isSingleLine = true
        ellipsize = null
        setHorizontallyScrolling(true)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        restart()
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        // 去掉行尾空白：LRC 文本常带尾随空格，它们不显示但会被 measureText 算进宽度，
        // 结果就是「停到句尾」时右边白白空出一截
        drawnText = text?.toString()?.trimEnd().orEmpty()
        super.setText(text, type)
        // 宽度此刻可能还是 0（刚 inflate 完），等一次布局再判断要不要滚
        post { restart() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 离开窗口时动画被 stop 掉了，回来要重新扫一遍（不清 key 的话会被 restart 当成「同一句，别动」）
        lastKey = ""
        post { restart() }
    }

    override fun onDetachedFromWindow() {
        stopAnimator()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) {
            lastKey = ""
            post { restart() }
        } else {
            stopAnimator()
        }
    }

    override fun onDraw(canvas: Canvas) {
        // 放得下：交给 TextView 正常画（居中、字体度量都一致）
        if (travel <= 0f || drawnText.isEmpty()) {
            super.onDraw(canvas)
            return
        }
        // 溢出：自己画，量多少画多少，与 super.onDraw 的排版无关
        val p = paint
        val fm = p.fontMetrics
        val baseline = height / 2f - (fm.ascent + fm.descent) / 2f
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.translate(-offset, 0f)
        canvas.drawText(drawnText, 0f, baseline, p)
        canvas.restore()
    }

    /** 告诉控件这句歌词大约还能显示多久（ms）；在 setText 之前调用才生效 */
    fun setSweepBudget(ms: Long) {
        budgetMs = ms.coerceIn(0L, 30_000L)
    }

    /**
     * 播放暂停时把滚动停在当前读到的地方，继续播放从原处接着走。
     *
     * 以前暂停是「归零 + 恢复时重扫」，长句读到一半被暂停再继续，字会被拽回句首，
     * 跟这次去掉回程的诉求是同一种观感问题。ValueAnimator 自带 pause/resume（API 19+），
     * 直接用它就不会丢进度。
     */
    fun setScrollPaused(paused: Boolean) {
        if (this.paused == paused) return
        this.paused = paused
        val a = animator
        if (paused) {
            a?.pause()
        } else if (a != null && a.isPaused) {
            a.resume()
        } else {
            // 没有在跑的动画（这句没溢出 / 已经扫完 / 从没启动）：重新判定一次
            restart()
        }
    }

    /**
     * 按当前文本 / 可用宽度 / 字号重新判定「不动 / 扫一遍」。
     *
     * 三者都没变时**直接返回，不碰已经扫到的进度** —— 这是「扫到尾又被拽回开头」的根因：
     * 同一个 View 的 restart 会被好几个入口重复触发（setText 后的 post、onSizeChanged
     * 里高度变化、onVisibilityChanged、onAttachedToWindow），每进来一次就把 offset
     * 归零 + 重启动画，同一句歌词在一秒内会被从头扫两三遍。
     */
    private fun restart() {
        // 用 drawnText（setText 里存的那份）：量它、画它，同一个字符串同一个 Paint
        val content = drawnText
        val avail = width - compoundPaddingLeft - compoundPaddingRight
        val key = "$content|$avail|${paint.textSize}"
        if (key == lastKey) {
            // 内容没变：只有「上次因为暂停 / 不可见 / 还没 attach 而没启动」才需要补一次启动
            if (pendingStart && canSweep()) {
                pendingStart = false
                startAnimator()
            }
            return
        }
        lastKey = key
        pendingStart = false

        stopAnimator()
        offset = 0f

        if (avail <= 0 || content.isEmpty()) {
            travel = 0f
            invalidate()
            return
        }

        val textWidth = paint.measureText(content)
        // 溢出量很小（< 6dp）也当放得下：否则「差几个像素」的句子会被拉成左对齐 + 微微横移，
        // 看起来既没居中、也没滚，是最难看的一种状态
        val overflow = textWidth - avail
        if (overflow <= dp(6f)) {
            travel = 0f
            gravity = Gravity.CENTER
            invalidate()
            return
        }

        // 溢出：这一档必须靠左排版，否则 TextView 会把超出的部分居中裁掉，开头就看不见了
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        travel = textWidth - avail + dp(10f)
        if (!canSweep()) {
            pendingStart = true
            invalidate()
            return
        }
        startAnimator()
    }

    /** 现在能不能滚：没暂停、挂在窗口上、可见、而且这句确实溢出 */
    private fun canSweep(): Boolean =
        !paused && isAttachedToWindow && visibility == VISIBLE && travel > 0f

    /**
     * 滚动节奏：**开头停一下 → 扫到句尾 → 就停在句尾**，整段跑完正好用完这句的停留时间。
     *
     * 为什么不再回到开头：一句只停留三四秒，回程的时间只能从「读句尾」里挤 ——
     * 观众刚扫到最后半句，字就开始往回跑，既没读完也看着晃。现在扫到尾就停住，
     * 剩下的时间全留给句尾；下一句 setText 时会 restart()，自然回到开头。
     *
     * 时间由 [setSweepBudget] 下发的预算按比例切：20% 看开头 / 30% 扫过去 / 余下 50% 停在句尾。
     * 拿不到预算时按 5s 兜底。
     */
    private fun startAnimator() {
        val dist = travel
        if (dist <= 0f) return
        // 兜底：万一还有上一段的动画在跑，先清掉，别让两个 animator 同时改 offset
        stopAnimator()

        val budget = (if (budgetMs > 0L) budgetMs else DEFAULT_BUDGET_MS.toLong())
            .coerceIn(MIN_BUDGET_MS.toLong(), MAX_BUDGET_MS.toLong()).toFloat()

        val fHold = HOLD_HEAD_FRAC
        val fSweepEnd = fHold + SWEEP_FRAC

        // 单向：0 → 停 → 扫到句尾 → 停在句尾（没有回程关键帧）
        val holder = PropertyValuesHolder.ofKeyframe(
            "offset",
            Keyframe.ofFloat(0f, 0f),
            Keyframe.ofFloat(fHold, 0f),
            Keyframe.ofFloat(fSweepEnd, dist),
            Keyframe.ofFloat(1f, dist),
        )

        animator = ValueAnimator.ofPropertyValuesHolder(holder).apply {
            duration = budget.toLong()
            interpolator = LinearInterpolator()
            addUpdateListener {
                offset = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // 自然结束时锁定在句尾，避免末帧回调缺失把字挂在半路
                    offset = dist
                    invalidate()
                    if (debugLog) Log.d(TAG, "sweep stop offset=$offset dist=$dist")
                }
            })
            if (debugLog) Log.d(TAG, "sweep start travel=$dist budget=$budget")
            start()
        }
    }

    private fun stopAnimator() {
        val a = animator ?: return
        animator = null
        // 先摘监听再 cancel：cancel 也会触发 onAnimationEnd，不摘的话会把 offset 强设成句尾
        a.removeAllListeners()
        a.cancel()
    }

    private fun dp(v: Float): Int = max(1, (v * resources.displayMetrics.density).toInt())

    private companion object {
        const val TAG = "ScrollTextView"

        /** 预算的上下限：太短来不及看，太长会拖到下一句 */
        const val MIN_BUDGET_MS = 1_200f
        const val MAX_BUDGET_MS = 8_000f

        /** 拿不到这句的停留时长时的兜底预算 */
        const val DEFAULT_BUDGET_MS = 5_000f

        /** 开头停留 / 扫过去 的时间占比；余下的 0.5 全部停在句尾（不再回程） */
        const val HOLD_HEAD_FRAC = 0.20f
        const val SWEEP_FRAC = 0.30f
    }
}
