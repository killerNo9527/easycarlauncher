package com.lf.launcher.home

/**
 * 桌面窗口的「可见状态」广播。
 *
 * 覆盖层是独立系统窗口，不会跟着 Activity 的可见性自动收放，必须有人告诉它
 * 「现在该不该露脸」。这个对象就是唯一的事实来源：
 *
 * ```
 * homeVisible = 桌面 resumed && 窗口有焦点 && 当前页 == 首页 && ViewPager 不在滑动
 * ```
 *
 * 三个条件缺一不可：
 * - 桌面前台：否则覆盖层会压在别的应用（包括我们自己的设置页）上面；
 * - 有焦点：下拉通知、系统弹窗、其它应用浮窗抢焦点时先收起来；
 * - 首页静止：滑动过程中卡片在动，而系统窗口只能用屏幕坐标硬贴，跟不上，索性整段隐藏。
 */
object LauncherWindowState {

    private var resumed = false
    private var windowFocused = true
    private var pageIndex = 0
    private var pagerScrolling = false

    private val listeners = mutableListOf<(Boolean) -> Unit>()

    val isHomeVisible: Boolean
        get() = resumed && windowFocused && pageIndex == 0 && !pagerScrolling

    fun setResumed(value: Boolean) = update { resumed = value }

    /**
     * 窗口焦点变化。
     *
     * 注意：覆盖层切成「可交互」后会自己抢焦点，桌面会收到 hasFocus=false，
     * 这不是"切走了"，调用方要先判断 [com.lf.launcher.map.MapOverlayWindow.isFocusable] 再决定是否置 false。
     */
    fun setWindowFocused(value: Boolean) = update { windowFocused = value }

    fun setPage(index: Int, scrolling: Boolean) = update {
        pageIndex = index
        pagerScrolling = scrolling
    }

    fun addListener(listener: (Boolean) -> Unit) {
        listeners += listener
        listener(isHomeVisible)
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners -= listener
    }

    private inline fun update(block: () -> Unit) {
        block()
        val visible = isHomeVisible
        // 复制一份，避免回调里增删监听导致并发修改
        listeners.toList().forEach { it(visible) }
    }
}
