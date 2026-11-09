package com.lf.launcher

import android.app.WallpaperManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.lf.launcher.ui.Permissions
import com.lf.launcher.ui.StatusTray
import com.lf.launcher.ui.SystemUi
import com.lf.launcher.ui.ThemedActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import android.content.Intent
import com.lf.launcher.apps.AppListFragment
import com.lf.launcher.databinding.ActivityLauncherBinding
import com.lf.launcher.home.HomeFragment
import com.lf.launcher.home.LauncherWindowState
import com.lf.launcher.diag.FlowLog
import com.lf.launcher.home.MapAppLauncher
import com.lf.launcher.music.MusicController
import com.lf.launcher.map.AppAlive
import com.lf.launcher.settings.Keys
import com.lf.launcher.settings.SettingsActivity
import com.lf.launcher.settings.SettingsStore
import com.lf.launcher.settings.StatusShortcuts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 桌面入口。骨架对照原版 activity_main：wallpaper → view_pager → statusBar → navigationBar。
 * M0 目标只有四件事：能装、能设默认、按 Home 回来、熄火重启不被抢。
 */
class LauncherActivity : ThemedActivity() {

    private lateinit var binding: ActivityLauncherBinding

    /** 状态栏右侧的信号 / Wi-Fi / 蓝牙：真实读写系统状态，随前台起停广播 */
    private lateinit var statusTray: StatusTray

    private val handler = Handler(Looper.getMainLooper())
    private val clock = object : Runnable {
        override fun run() {
            renderStatusBar()
            handler.postDelayed(this, 15_000L)
        }
    }

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.CHINA)
    private val dateFmt = SimpleDateFormat("M月d日 EEEE", Locale.CHINA)

    /** 上次画到状态栏左上角的包名序列；一样就不重建那一排图标 */
    private var shortcutsSignature = ""

    /** 自动拉起地图：是否还有一次「待返回」的流程在跑 */
    private var autoMapPending = false

    /** 下一次 onResume 是自动流程自己切回来的，别再拉起一次 */
    private var suppressAutoMap = false

    /**
     * 上次自动拉起的时间，用于冷却。
     *
     * 初始值故意是负数，不能是 0：`elapsedRealtime()` 在开机时也是从 0 开始涨的，
     * 放 0 的话开机前 15 秒里算出来就是「刚刚拉过」，整个开机流程的自动拉地图
     * 会被冷却直接吃掉（实测日志：`自动地图：冷却中（还差 4081ms）`，那时系统才起 11 秒）。
     * 表现就是开机后地图卡一直是空的，高德只能等后面某次广播才被唤起。
     */
    private var lastAutoMapAt = -AUTO_MAP_COOLDOWN_MS

    /** 桌面自己是否在前台：自动拉起地图前要再确认一次，防止抢走用户刚打开的页面 */
    private var resumed = false

    /**
     * 安全模式：只画 [CrashGuard] 那个极简页。
     *
     * 这个状态下 `binding` 是 **未初始化** 的（onCreate 提前 return 了），
     * 所以所有碰 binding 的生命周期回调都必须先看这个标记，否则就是连环崩。
     */
    private var safeMode = false

    /** 2 秒倒计时是否已经排上（由 onPause 排，保证是"地图真的在前面"才开始算） */
    private var autoMapBackScheduled = false

    /**
     * 拉过高德之后，多久之内再被它盖上来都算**同一次**开机流程。
     *
     * 高德的启动不是一步到位：`UsbFillActivity`（启动中转页）→ 自己再跳
     * `MainMapActivity`。第一跳会让桌面 onPause，2 秒后我们把桌面抢回来，
     * 紧接着它第二跳又把桌面盖住 —— 那时 `autoMapPending` 已经清掉了，
     * 没人管，桌面就永远回不来（实测会卡在高德主界面上，音乐也拉不起来）。
     * 这个窗口让第二跳、第三跳都还算在这一次流程里。
     */
    private val autoMapSettleMs = 8_000L

    /**
     * 音乐是这一刻被拉起的。
     *
     * 用来把「音乐盖上来造成的 onPause」从地图流程里排除掉：
     * 两者都在拉起后几秒内让桌面 onPause，光看时间分不清是谁，
     * 误判的后果是 `musicBack` 以为地图在收尾、主动让路，桌面就再也回不来
     * （实测会卡在 md3Music 界面）。
     */
    private var musicFlowAt = 0L

    /** 音乐「拉起 → 切回」这段窗口，期间发生的 onPause 都算音乐造成的 */
    private val musicFlowWindowMs = 2_500L

    /**
     * 上次**真正把高德拉起来**的时刻。
     *
     * [autoMapPending] 会被 watchdog 提前清掉（高德没在 4 秒内浮上来时），
     * 那时候它还在后台冷启动、还没加载完 —— 只看 pending 会误判成"地图流程结束了"。
     * 音乐自启靠这个时间戳避开高德的启动窗口。
     */
    private var lastAutoMapLaunchAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 连续崩过 3 次：只画极简页，卡片/地图/音乐一律不碰 —— 崩点很可能就在它们里面，
        // 再跑一遍就是同一个结果。这里必须排在 SettingsStore.init 之前：
        // 万一是配置读取崩的，也得进得来。
        if (CrashGuard.isSafeMode(this)) {
            safeMode = true
            setContentView(
                CrashGuard.buildSafeView(this) {
                    // 先清标记再重建：onCreate 会重新判断 isSafeMode()，这次走正常流程。
                    //
                    // 这里**不能**顺手把 safeMode 置成 false —— 重建前还要走一遍 onPause，
                    // 那一堆回调全靠 safeMode 跳过对 binding / statusTray 的访问，
                    // 提前放开就会 `lateinit property statusTray has not been initialized`，
                    // 于是"重启桌面"自己崩一次，又被兜底拉回安全模式，永远出不去。
                    CrashGuard.exitSafeMode(this)
                    recreate()
                },
            )
            // 安全模式页也要占满整屏：状态栏压在上面很显眼，而且这时候没别的界面可看
            SystemUi.apply(this)
            FlowLog.w(FLOW, "进入安全模式：${CrashGuard.lastCrash(this)}")
            return
        }

        SettingsStore.init(this)
        binding = ActivityLauncherBinding.inflate(layoutInflater)
        setContentView(binding.root)

        SystemUi.attachContentInset(binding.contentRoot)
        applySystemUi()
        setupPager()
        // 日夜切换会重建 Activity，页码要接着上次的，不能被打回首页
        savedInstanceState?.getInt(STATE_PAGE, 0)?.let { page ->
            if (page > 0) binding.viewPager.setCurrentItem(page, false)
        }
        setupNav()
        applyWallpaper()
        renderStatusBar()

        statusTray = StatusTray(this, binding.root)
        // 状态栏右上角的齿轮和底栏那个是同一个入口，点哪儿都进桌面设置
        binding.statusSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        // 左上角三个点：没开功能就送他去设置里打开，已经开了但一个都没选就直接进选择页
        binding.statusShortcutsMore.setOnClickListener { openShortcutEntry() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (safeMode) return
        outState.putInt(STATE_PAGE, binding.viewPager.currentItem)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (safeMode) return
        // 从设置页等其它页面返回时系统栏标志会丢失，必须重新申请
        applySystemUi()
        // 设置页里可能改过「用系统栏 / 四边留白」，回来要重算一次
        SystemUi.refresh(binding.contentRoot)
        // 均衡器挂在全局输出混音上，进程被回收后 effect 会丢；每次回前台按存的配置重挂一次。
        // 设置页里改过开关也是靠这一步生效（那边只写配置，不下发）。
        com.lf.launcher.eq.EqSettings.restore()
        // 音乐卡：桌面是 HOME，开机就起，那时 md3Music 往往还没启动，它的会话会直接
        // 拒绝连接（onGetSession 返回 null），只能靠重试等到它开始放歌。
        // 回到前台的这一刻用户就在看卡片，别再等 3 秒退避
        com.lf.launcher.music.MusicController.retryNow()
        // 是从我们自己的二级页（设置 / 选择器）回来的：这时候把人拉去地图最招人烦，
        // 而且要在这里就把 returnToHome 读出来 —— 下面马上会清掉它
        val fromOwnPage = returnToHome
        if (returnToHome) {
            returnToHome = false
            binding.viewPager.setCurrentItem(0, false)
        }
        applySettings()
        // 状态栏图标要反映"离开期间变过的"系统状态，所以回前台先订阅再刷一次
        statusTray.start()
        handler.post(clock)
        // 覆盖层是独立窗口，桌面前台状态必须显式告诉它，否则会飘在别的应用上
        LauncherWindowState.setPage(binding.viewPager.currentItem, false)
        LauncherWindowState.setResumed(true)
        renderStatusBar()
        // 首次装完进桌面时把权限一次问齐（见 maybeAskPermissions）。
        // 返回 true 表示刚弹了系统授权框 —— 这时候别再自动拉起地图，
        // 否则授权框会被地图盖掉，用户还会以为是自己点错了。
        // 注意不能写进 fromOwnPage 的短路表达式里：那样 fromOwnPage 为真时它压根不执行
        val askingPermission = maybeAskPermissions()
        scheduleAutoMap(skip = fromOwnPage || askingPermission)
        maybeAutoStartMusic(skip = fromOwnPage || askingPermission)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (safeMode) return
        if (hasFocus) applySystemUi()
        // 高德悬浮窗永不抢焦点（TYPE_APPLICATION_OVERLAY + NOT_FOCUSABLE 由对方决定），
        // 所以这里不用为它让路：失焦就是真的切走了
        LauncherWindowState.setWindowFocused(hasFocus)
    }

    /**
     * 当前活着的首页 Fragment。
     *
     * 用 fragmentManager 找而不是从 PagerAdapter 取：FragmentStateAdapter 不暴露实例。
     * 停在应用页且首页被回收时这里是 null —— 那种情况下 onViewCreated 里的 bindMusic()
     * 会再兜一次底，不会漏掉显隐。
     */
    private fun homeFragment(): HomeFragment? =
        supportFragmentManager.fragments.filterIsInstance<HomeFragment>().firstOrNull()

    /** 设置项即时生效：状态栏与底部导航的显隐 */
    private fun applySettings() {
        val statusVisible = SettingsStore.getBool(Keys.SHOW_STATUS_BAR, true)
        binding.statusBar.visibility = if (statusVisible) View.VISIBLE else View.GONE
        statusTray.setBarVisible(statusVisible)
        val navVisible = SettingsStore.getBool(Keys.SHOW_NAV_BAR, true)
        binding.bottomBar.visibility = if (navVisible) View.VISIBLE else View.GONE
        // 底栏没了，切歌的入口就只剩音乐卡里那套 —— 两边的显隐必须同步，否则会出现
        // 「底栏和卡片各有一套」或者「一套都没有」的空窗
        homeFragment()?.applyMediaControlsVisibility()
        // 状态栏开着才有左上角那条快捷方式；它自己的开关在设置里，这里按配置重排
        renderShortcuts()

        // 底栏一关，页面给它让出的那条高度也必须一起还回去，否则底下留一条空带
        val pagerLp = binding.viewPager.layoutParams as? android.view.ViewGroup.MarginLayoutParams
        if (pagerLp != null) {
            val inset = if (navVisible) {
                resources.getDimensionPixelSize(R.dimen.pager_bottom_inset)
            } else {
                0
            }
            if (pagerLp.bottomMargin != inset) {
                pagerLp.bottomMargin = inset
                binding.viewPager.layoutParams = pagerLp
            }
        }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        if (safeMode) {
            LauncherWindowState.setResumed(false)
            return
        }
        // 自动拉起地图的流程：桌面被切到后台 = 地图已经盖上来了，2 秒从这一刻开始算。
        // 用 inAutoMapFlow() 而不是只看 autoMapPending：高德启动中转页跳主界面会
        // 造成第二次 onPause，那一次 pending 已经清了，但流程还没收尾（见 autoMapSettleMs）
        val now = SystemClock.elapsedRealtime()
        // 这次 onPause 是音乐盖上来造成的，不归地图流程管
        val musicJustLaunched = musicFlowAt > 0 && now - musicFlowAt < musicFlowWindowMs
        if (!musicJustLaunched && !autoMapBackScheduled && inAutoMapFlow()) {
            autoMapBackScheduled = true
            FlowLog.i(FLOW, "自动地图：桌面被盖住，${AUTO_MAP_BACK_MS}ms 后切回")
            handler.postDelayed(autoMapBack, AUTO_MAP_BACK_MS)
        }
        handler.removeCallbacks(clock)
        // 状态栏图标在后台不用跟着变：广播一停，省掉每秒好几次的 RSSI 回调
        statusTray.stop()
        // 设置页、地图选择器盖上来时桌面 onPause；高德悬浮窗不是我们的窗口，
        // 必须立刻喊收起，否则它会压在我们自己的二级页面上
        LauncherWindowState.setResumed(false)
    }

    override fun onStop() {
        super.onStop()
        // 被别的应用完全挡住时桌面会 stop：这时任何模式都该收起浮层
        LauncherWindowState.setResumed(false)
    }

    override fun onDestroy() {
        handler.removeCallbacks(clock)
        handler.removeCallbacks(autoMapLaunch)
        handler.removeCallbacks(autoMapBack)
        handler.removeCallbacks(autoMapWatchdog)
        handler.removeCallbacks(musicAutoStart)
        handler.removeCallbacks(musicBack)
        handler.removeCallbacks(musicReturn)
        super.onDestroy()
    }

    /** 桌面里按返回不退出，只回首页 */
    @Deprecated("车机桌面不应退出")
    override fun onBackPressed() {
        if (safeMode) return
        if (binding.viewPager.currentItem != 0) {
            binding.viewPager.setCurrentItem(0, true)
        }
    }

    /**
     * 首次装完进桌面时，把运行时权限一次问齐。
     *
     * ## 为什么改成"一进来就问"
     *
     * 原来的策略是"用到才申请"——点信号格才要电话、开自动定位才要位置。对老用户舒服，
     * 但新用户装完看到的是一个天气空着、信号格空着的桌面，**没有任何线索**告诉他要先点
     * 那个图标才会触发授权。所以第一次进桌面就一次问完，之后永久静默。
     *
     * ## 只问一次
     *
     * 标记 [Keys.PERMISSION_ASKED] 在请求**之前**落盘：授权框会让 Activity 走一轮
     * onPause/onResume，标记写在后面会被这轮 onResume 再触发一次，变成连弹两轮。
     * 用户拒绝也不重问 —— 缺的交给设置页「权限」分组补，不骚扰。
     *
     * @return true 表示刚弹了系统授权框，调用方要跳过「自动拉起地图」
     */
    private fun maybeAskPermissions(): Boolean {
        if (SettingsStore.getBool(Keys.PERMISSION_ASKED, false)) {
            maybeAskOverlay()
            return false
        }
        SettingsStore.setBool(Keys.PERMISSION_ASKED, true)
        val missing = Permissions.missingRuntime(this)
        if (missing.isEmpty()) {
            maybeAskOverlay()
            return false
        }
        runCatching { requestPermissions(missing.toTypedArray(), REQ_ALL) }
            .onFailure { Log.w(TAG, "批量申请权限失败：$it") }
        return true
    }

    /**
     * 悬浮窗权限的引导。
     *
     * 它不能 `requestPermissions` —— 属于特殊权限，只能跳系统页让用户自己开。
     * 所以单独一次说明弹窗，且**不拦路**：不给也能用，只是地图卡四角变直角。
     */
    private fun maybeAskOverlay() {
        if (SettingsStore.getBool(Keys.OVERLAY_ASKED, false)) return
        if (Permissions.overlayGranted(this)) return
        SettingsStore.setBool(Keys.OVERLAY_ASKED, true)
        // 让桌面先画完一帧：刚弹完系统授权框紧接着再来一个自己的对话框会很挤
        handler.postDelayed({ showOverlayDialog() }, OVERLAY_PROMPT_DELAY_MS)
    }

    private fun showOverlayDialog() {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("还差一个悬浮窗权限")
            .setMessage(
                "地图卡显示高德时，要在它的窗口上盖一层圆角遮罩，这需要「显示在其他应用上层」。\n\n" +
                    "不给也能用，只是地图卡四角会变成直角。随时可以在 设置 → 权限 里补开。",
            )
            .setPositiveButton("去开启") { _, _ ->
                if (!Permissions.requestOverlay(this)) {
                    toast("这台车机没有这个设置页，请到 系统设置 → 应用 → 权限 里手动打开")
                }
            }
            .setNegativeButton("暂不", null)
            .show()
    }

    /**
     * 状态栏信号格第一次被点时申请的 `READ_PHONE_STATE`。
     *
     * 首次启动已经统一要过了，这条是用户当时拒了、后来又去点信号格的补救路径。
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            StatusTray.REQ_PHONE -> statusTray.onPhonePermissionResult(
                grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            )

            REQ_ALL -> {
                val missing = Permissions.missingRuntime(this)
                if (missing.isEmpty()) {
                    toast("权限已就绪")
                } else {
                    // 用户点了拒绝也算正常结果，不追着问：告诉他去哪儿补就行
                    toast("还差「${Permissions.describe(missing)}」，可在 设置 → 权限 里补")
                }
                maybeAskOverlay()
            }
        }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * 系统状态栏 / 导航栏策略。
     *
     * 默认（[Keys.USE_SYSTEM_BARS] 关）沿用以前的独占整屏；打开后显示系统两条栏，
     * 内容由 [SystemUi.attachContentInset] 按 insets + 自定义留白让位。
     */
    private fun applySystemUi() {
        SystemUi.apply(this)
    }

    private fun setupPager() {
        binding.viewPager.adapter = PagerAdapter(this)
        binding.viewPager.offscreenPageLimit = 1
        // 支持左右滑动切页（首页 ↔ 应用），底部按钮仍保留作为兜底入口
        binding.viewPager.isUserInputEnabled = true
        binding.viewPager.registerOnPageChangeCallback(
            object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    renderNav(position)
                    LauncherWindowState.setPage(position, scrolling)
                }

                override fun onPageScrollStateChanged(state: Int) {
                    scrolling = state != androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_IDLE
                    // 滑动期间先收起覆盖层：它是屏幕坐标的系统窗口，跟不了卡片位移
                    LauncherWindowState.setPage(binding.viewPager.currentItem, scrolling)
                }
            },
        )
    }

    private var scrolling = false

    private fun setupNav() {
        binding.navHome.setOnClickListener { binding.viewPager.setCurrentItem(0, true) }
        binding.navApps.setOnClickListener { binding.viewPager.setCurrentItem(1, true) }
        binding.navSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        renderNav(0)
    }

    private fun renderNav(position: Int) {
        // 底栏右侧是图标不是文字，用 tint 表示当前页
        val on = getColor(R.color.text_primary)
        val off = getColor(R.color.text_tertiary)
        binding.navHome.setColorFilter(if (position == 0) on else off)
        binding.navApps.setColorFilter(if (position == 1) on else off)
    }

    private fun renderStatusBar() {
        val now = Date()
        binding.statusTime.text = timeFmt.format(now)
        binding.statusDate.text = dateFmt.format(now)
    }

    // ---------------------------------------------------------- 自动拉起地图

    /**
     * 「打开桌面后自动启动地图，两秒后返回」。
     *
     * 为什么需要这一手：高德的悬浮窗（`showmap` 广播）**只能唤到已经活着的进程**，
     * 冷启动前发广播它连进程都不会起来，卡片上就是一片空。所以桌面回到前台时先把它
     * 正常启动一次，让它在后台常驻并挂上悬浮窗，两秒后再把桌面切回来。
     *
     * 三个必须防的坑：
     * 1. **自己切回来不能再次触发** —— 否则拉起 → 回来 → 再拉起，无限循环。
     *    `suppressAutoMap` 就是这个"这次 onResume 是自己造成的"标记。
     * 2. **反复进出别的应用不能每次都弹** —— 用 [AUTO_MAP_COOLDOWN_MS] 冷却，
     *    从设置页回来、从应用列表回来不会被打断。
     * 3. **onPause 里不能取消** —— 地图一起来桌面就 onPause 了，在那儿取消等于永远回不来。
     */
    private fun scheduleAutoMap(skip: Boolean = false) {
        if (!SettingsStore.getBool(Keys.AUTO_MAP_ON_OPEN, true)) return
        if (MapAppLauncher.selectedPackage().isEmpty()) return
        // 开机那一下已经拉过了：日常回桌面、换日夜重建 Activity 都不再重复。
        // 这里必须用进程级标记，实例字段在 recreate() 之后会清零（见 autoMapDoneThisProcess）
        if (autoMapDoneThisProcess) {
            // 刚拉过、还在收尾窗口里：直接跳过。高德冷启动要好几秒，
            // 这期间做存活判断只会得到一个假的"没跑"
            if (inAutoMapFlow()) {
                FlowLog.i(FLOW, "自动地图：本次开机已经拉过，跳过")
                return
            }
            // 收尾窗口过了，问一句"它还在吗"。
            // AppAlive 只有在这个 ROM 真的允许看第三方进程时才给得出 false，
            // 给不出（null）就当"还在"，维持"一次开机只拉一次"的行为。
            // 换句话说：能自适应就自适应，不能就退回保守策略。
            val alive = AppAlive.isRunning(this, MapAppLauncher.selectedPackage())
            if (alive != false) {
                Log.i(
                    FLOW,
                    "自动地图：本次开机已经拉过" + when (alive) {
                        true -> "，高德还在跑"
                        else -> "（无法判断存活，按还在算）"
                    },
                )
                return
            }
            FlowLog.i(FLOW, "自动地图：高德已经不在运行，补拉一次")
            autoMapDoneThisProcess = false
        }
        if (suppressAutoMap) {
            // 是刚才那次自动流程把自己切回来的：吃掉标记，不再拉起。
            // 冷却时间也要从这一刻重新算 —— 否则开机那一串流程走下来
            // （地图 → 音乐 → 回桌面）早就超过 15 秒，最后这一次回桌面会立刻
            // 再拉一次地图，看起来就是「刚回到桌面又被拽去高德」。
            suppressAutoMap = false
            lastAutoMapAt = SystemClock.elapsedRealtime()
            FlowLog.i(FLOW, "自动地图：这次回桌面是流程自己造成的，跳过并重置冷却")
            return
        }
        val now = SystemClock.elapsedRealtime()
        // 从自己的二级页回来也重新计时：刚改完设置就弹地图会让人以为点错了
        if (skip) {
            lastAutoMapAt = now
            FlowLog.i(FLOW, "自动地图：skip（二级页/权限框），重置冷却")
            return
        }
        if (now - lastAutoMapAt < AUTO_MAP_COOLDOWN_MS) {
            FlowLog.i(FLOW, "自动地图：冷却中（还差 ${AUTO_MAP_COOLDOWN_MS - (now - lastAutoMapAt)}ms）")
            return
        }
        lastAutoMapAt = now
        autoMapPending = true
        autoMapBackScheduled = false
        FlowLog.i(FLOW, "自动地图：${AUTO_MAP_OPEN_DELAY_MS}ms 后拉起")
        // 先让桌面画完一帧再拉起：否则切过去时桌面上还是空的。
        // 先 remove 再 post：onResume 可能连着来好几次，队列里堆两个 Runnable 的话
        // 第二次执行时 autoMapPending 仍是 true，会把高德**再拉一次**。
        handler.removeCallbacks(autoMapLaunch)
        handler.postDelayed(autoMapLaunch, AUTO_MAP_OPEN_DELAY_MS)
    }

    private val autoMapLaunch = Runnable {
        if (!autoMapPending) return@Runnable
        // 这 400ms 里如果用户已经点了别的（比如进了设置页），就别抢前台了
        if (!resumed) {
            autoMapPending = false
            FlowLog.i(FLOW, "自动地图：已不在前台，放弃本次")
            return@Runnable
        }
        if (!MapAppLauncher.launchFullscreen(this)) {
            autoMapPending = false
            FlowLog.w(FLOW, "自动地图：launchFullscreen 返回 false，放弃本次")
            return@Runnable
        }
        lastAutoMapLaunchAt = SystemClock.elapsedRealtime()
        autoMapDoneThisProcess = true
        FlowLog.i(FLOW, "自动地图：已拉起，等它盖上来")
        // 2 秒倒计时**不在这里起算**：高德冷启动要 1~2 秒，从这里算的话地图可能只露出半秒。
        // 真正的起点是 onPause（说明地图已经盖上来）。这里只放一个兜底的看门狗，
        // 防止"地图压根没起来但 pending 一直挂着"，那样下次退到后台会把桌面硬拉回来。
        handler.postDelayed(autoMapWatchdog, AUTO_MAP_WATCHDOG_MS)
    }

    /** 地图没起来（或起得太慢）时清掉状态，别让它留到下一次 onPause 作乱 */
    private val autoMapWatchdog = Runnable {
        if (!autoMapPending || autoMapBackScheduled) return@Runnable
        autoMapPending = false
    }

    private val autoMapBack = Runnable {
        autoMapBackScheduled = false
        // 用 inAutoMapFlow() 而不是 autoMapPending：高德跳主界面的第二次 onPause
        // 也需要被切回来，那时候 pending 已经是 false 了
        if (!inAutoMapFlow()) return@Runnable
        autoMapPending = false
        suppressAutoMap = true
        FlowLog.i(FLOW, "自动地图：切回桌面")
        // 自己是 singleTask：这个 Intent 不会新建实例，只是把桌面所在的任务提到前台
        runCatching {
            startActivity(
                Intent(this, LauncherActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * 现在是不是还在「自动拉起地图」这一次流程里。
     *
     * 两条命中条件：
     * 1. `autoMapPending` —— 刚拉起、还没切回
     * 2. 已经拉过，且距拉起不到 [autoMapSettleMs] —— 高德会从启动中转页再跳主界面，
     *    第二次 onPause 也要算在同一次流程里，否则桌面就回不来了
     */
    private fun inAutoMapFlow(): Boolean {
        if (autoMapPending) return true
        if (!autoMapDoneThisProcess) return false
        return SystemClock.elapsedRealtime() - lastAutoMapLaunchAt < autoMapSettleMs
    }

    /**
     * 点地图卡拉起地图，然后自动回桌面。
     *
     * 点地图卡的场景是「悬浮窗没出来、卡片是空的」——用户要的是把地图进程喊起来
     * 让它把悬浮窗挂到卡片上，不是要停在它的全屏界面。所以拉起后复用自动流程的
     * 收尾机制（`autoMapSettleMs` 窗口 + `autoMapBack`），2 秒后自己回来。
     *
     * 悬浮窗真正显示之后，用户点卡片点到的是**高德自己的窗口**，那一次不由我们管，
     * 想用全屏导航就在那时候点。
     */
    fun launchMapAndComeBack() {
        if (!MapAppLauncher.launchFullscreen(this)) return
        lastAutoMapLaunchAt = SystemClock.elapsedRealtime()
        autoMapDoneThisProcess = true
        FlowLog.i(FLOW, "点地图卡：已拉起，${AUTO_MAP_BACK_MS}ms 后回桌面")
    }

    // ---------------------------------------------------------- 开机自动播放音乐

    /** 开机后多久之内才允许自动拉音乐。过了这个窗口，用户每次按 HOME 回来都不该被弹进音乐 App */
    private val bootMusicWindowMs = 120_000L

    /**
     * 排给 [musicAutoStart] 的延时。
     *
     * 不能在这一趟 onResume 里直接拉：那时「自动拉起地图」刚排好队（400ms 后拉起高德），
     * 两个流程同时抢前台会互相盖。地图那趟从拉起到切回大约 5 秒，所以开了自动地图就多等一会；
     * 没开的话桌面已经空着，早点拉没坏处。
     */
    private val musicAutoStartDelayNoMap = 1_200L
    private val musicAutoStartDelayWithMap = 3_000L

    /** 地图流程还没收尾时的重试间隔：等它把前台还回来再动手 */
    private val musicWaitMapMs = 1_500L

    /**
     * 高德被拉起后要给它多久的清净。
     *
     * 它不是"露脸就算完"——冷启动后还要加载地图数据，这期间被别的应用盖上去，
     * 回来就是半张图。
     *
     * 高德没有公开的"加载完成"信号（私有广播里只有导航数据和大灯状态，
     * `UsageStatsManager` 只能判断"在前台"不等于"加载完"且要多申请特殊权限），
     * 所以只能在它被拉起后按时间让路。做成可调是因为机器差异太大：
     * 模拟器冷启动要 8 秒量级，真车机通常 1~2 秒。
     */
    private val musicQuietAfterMapMs: Long
        get() = SettingsStore.getString(Keys.MUSIC_MAP_QUIET, "1500").toLongOrNull() ?: 1_500L

    /** 等地图让路的次数上限，防止异常情况下一直挂着 */
    private val musicWaitMax = 24

    /** 已经在等地图让路多少轮 */
    private var musicWaitCount = 0

    /** 拉起音乐后多久切回桌面：给它时间把界面画出来，剩下的初始化在后台自己跑完 */
    private val musicBackDelayMs = 1_500L

    /** 开机自动播放这辈子只试一次，别每次回桌面都去拉 */
    private var autoMusicTried = false

    /**
     * 开机后把 md3Music 拉起来，续播上一次的队列。
     *
     * 为什么必须由桌面来做：md3Music 的 manifest 里没有 BOOT_COMPLETED 接收者，
     * dex 里也没有任何「开机自启 / 自动播放」设置项。系统自启管理最多把它的**进程**
     * 拉起来，没有界面就不会建播放队列 —— 实测开机一分半后它 0 个会话、0 个播放器，
     * 桌面只能显示「暂无播放」。
     *
     * 反过来，只要它的启动界面被拉起一次，就会自己恢复上次队列并开始播放
     * （实测 force-stop 后只 `am start`，12 秒内会话 `state=3`；中途按 HOME 也不影响，
     * 后台照样把播放跑起来）。所以这里拉一下、[musicBackDelayMs] 后切回桌面即可。
     *
     * 只在开机窗口内做，是因为它是 HOME：用户平时按 HOME 回来也会走 onResume，
     * 那时候再把他弹进音乐 App 就成了打扰。
     */
    private fun maybeAutoStartMusic(skip: Boolean = false) {
        if (autoMusicTried) return
        // 进程级：日夜切换重建 Activity 后 autoMusicTried 会清零，靠这个兜住
        if (autoMusicDoneThisProcess) return
        if (SystemClock.elapsedRealtime() > bootMusicWindowMs) {
            autoMusicTried = true
            return
        }
        // 从设置页/权限框回来：这一次先不动，下一次 onResume 再说
        if (skip) return
        if (!SettingsStore.getBool(Keys.MUSIC_AUTOSTART, true)) {
            autoMusicTried = true
            return
        }
        // 已经在放（比如上次熄火时音乐没停、系统自己拉起来了）：不要多此一举
        if (MusicController.isConnected) {
            autoMusicTried = true
            return
        }
        // 这一刻通常正好排在「自动拉起地图」后面，直接拉会抢前台，交给延时任务
        handler.removeCallbacks(musicAutoStart)
        musicWaitCount = 0
        handler.postDelayed(
            musicAutoStart,
            if (autoMapPending) musicAutoStartDelayWithMap else musicAutoStartDelayNoMap,
        )
    }

    /**
     * 真正去拉音乐的那一下。
     *
     * 三条硬约束：
     * 1. **地图流程还在跑就别插队** —— 那时候前台是高德（正在冷启动、还没加载完），
     *    音乐一盖上去，高德就永远停在那半张图上；等它收尾再排一次。
     * 2. **桌面不在前台就放手** —— 说明别的东西还盖着，等下次 onResume 重排；
     *    这里不重复自己，否则前台一直不是自己就成了死循环。
     * 3. 真正拉起后由 [musicBack] 负责回桌面，本任务不再插手。
     */
    private val musicAutoStart = object : Runnable {
        override fun run() {
            if (autoMusicTried) return
            if (SystemClock.elapsedRealtime() > bootMusicWindowMs) {
                autoMusicTried = true
                return
            }
            if (MusicController.isConnected) {
                autoMusicTried = true
                return
            }
            val now = SystemClock.elapsedRealtime()
            // 高德刚被拉起来没多久：它可能还在冷启动、还在加载地图数据，
            // 这时候插进去就是把它停在半张图上。注意不能只看 autoMapPending ——
            // watchdog 会在 4 秒后把它清成 false，那时高德其实还没起完。
            val mapBusy = autoMapPending || autoMapBackScheduled ||
                (lastAutoMapLaunchAt > 0 && now - lastAutoMapLaunchAt < musicQuietAfterMapMs)
            if (mapBusy) {
                if (++musicWaitCount > musicWaitMax) {
                    autoMusicTried = true
                    FlowLog.w(FLOW, "开机音乐：等地图让路 ${musicWaitMax} 轮仍不空，放弃")
                    return
                }
                Log.i(
                    FLOW,
                    "开机音乐：地图流程还占着" +
                        "（pending=$autoMapPending back=$autoMapBackScheduled " +
                        "距拉起高德=${now - lastAutoMapLaunchAt}ms），${musicWaitMapMs}ms 后再看",
                )
                handler.postDelayed(this, musicWaitMapMs)
                return
            }
            if (!resumed) {
                musicWaitCount = 0
                FlowLog.i(FLOW, "开机音乐：桌面不在前台，等下次 onResume 重排")
                return
            }
            autoMusicTried = true
            autoMusicDoneThisProcess = true
            musicWaitCount = 0
            if (!MusicController.autoStart()) {
                FlowLog.w(FLOW, "开机音乐：拉起失败（没有安装或没有启动入口）")
                return
            }
            FlowLog.i(FLOW, "开机音乐：已拉起，${musicBackDelayMs}ms 后回桌面")
            musicFlowAt = SystemClock.elapsedRealtime()
            handler.postDelayed(musicBack, musicBackDelayMs)
        }
    }

    /**
     * 音乐盖上来之后把自己请回前台。
     *
     * - [resumed] 为 true：音乐还没起来（冷启动慢），什么都不用做，它会在后台自己跑完
     * - `autoMapPending` 还在：此刻前台可能是**高德**而不是音乐，切回去会把地图打断，
     *   交给 autoMapBack 收尾，这里过会儿再看
     * - 真正的"自己造成的回桌面"：一定要打上 [suppressAutoMap]，
     *   否则这次 onResume 会被当成普通回桌面，十几秒后再去拉一次高德
     */
    private val musicBack = Runnable {
        if (resumed) {
            FlowLog.i(FLOW, "开机音乐：音乐还没盖上来，不用切回桌面")
            return@Runnable
        }
        // 拉起音乐的那一刻已经确认过地图流程没在跑；这里再 busy，说明正好撞上
        // autoMapBack 的收尾窗口，让它把桌面带回来就行，别两边抢
        if (autoMapPending || autoMapBackScheduled) {
            FlowLog.i(FLOW, "开机音乐：回桌面时地图流程正在收尾，交给它，不抢")
            return@Runnable
        }
        FlowLog.i(FLOW, "开机音乐：切回桌面，并压掉这次的自动拉地图")
        musicFlowAt = 0
        suppressAutoMap = true
        runCatching {
            startActivity(
                Intent(this, LauncherActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    // ---------------------------------------------------------- 双击音乐卡的回桌面

    /** 双击回桌面的已尝试次数，防止极端情况下无限重排 */
    private var musicReturnTries = 0

    /**
     * 双击音乐卡兜底：打开音乐界面 [delayMs] 后把桌面请回前台。
     *
     * 和开机那趟的 [musicBack] 不一样 —— 那个遇到「地图正在收尾」会主动让路然后**结束**，
     * 因为开机时地图和音乐都是流程自己发起的，让谁收尾都行。
     * 这里是用户主动双击，桌面**必须**回来，所以让路只是重排，不是放弃。
     */
    fun comeBackAfterMusic(delayMs: Long = 1_000L) {
        musicReturnTries = 0
        handler.removeCallbacks(musicReturn)
        handler.postDelayed(musicReturn, delayMs)
    }

    private val musicReturn = object : Runnable {
        override fun run() {
            if (resumed) {
                // 已经在前台：要么音乐没盖上来，要么上一次已经切回来了
                musicReturnTries = 0
                return
            }
            if (musicReturnTries >= 10) {
                FlowLog.w(FLOW, "双击回桌面：重试 10 次仍未回到前台，放弃")
                musicReturnTries = 0
                return
            }
            musicReturnTries++
            // 地图正在收尾：让它先把桌面带回来，省得两边抢；但过一会一定要复查
            if (autoMapPending || autoMapBackScheduled) {
                FlowLog.i(FLOW, "双击回桌面：地图流程在收尾，600ms 后重试（第 $musicReturnTries 次）")
                handler.postDelayed(this, 600)
                return
            }
            // 这次 onResume 是自己造成的，压掉自动拉地图，免得又弹一次高德
            suppressAutoMap = true
            musicFlowAt = 0
            val ok = runCatching {
                startActivity(
                    Intent(this@LauncherActivity, LauncherActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }.onFailure { FlowLog.w(FLOW, "双击回桌面：startActivity 失败 $it") }.isSuccess
            FlowLog.i(FLOW, "双击回桌面：切回桌面 ok=$ok（第 $musicReturnTries 次）")
            // 700ms 后复查：还没回到前台就再来一次（上面 resumed 会直接结束）
            handler.postDelayed(this, 700)
        }
    }

    // ---------------------------------------------------------- 左上角快捷应用

    /**
     * 左上角那一条：开关打开且至少选了一个 -> 一排应用图标；否则 -> 三个点。
     *
     * 为什么记 [shortcutsSignature] 而不是每次 onResume 都重建：
     * `statusTray.start()` 之后 onResume 会被调很多次（每次从别的应用回来都算），
     * 每次都 `removeAllViews()` + 重新 setImageDrawable 会在车机上闪一下。
     * 只有"选了什么、什么顺序"真的变了才重建。
     */
    private fun renderShortcuts() {
        val entries = if (StatusShortcuts.enabled()) StatusShortcuts.resolve(this) else emptyList()
        if (entries.isEmpty()) {
            binding.statusShortcuts.removeAllViews()
            binding.statusShortcutsMore.visibility = View.VISIBLE
            shortcutsSignature = ""
            return
        }
        binding.statusShortcutsMore.visibility = View.GONE

        val signature = entries.joinToString(",") { it.packageName }
        if (signature == shortcutsSignature) return
        shortcutsSignature = signature

        val box = binding.statusShortcuts
        box.removeAllViews()
        val touch = resources.getDimensionPixelSize(R.dimen.status_shortcut_touch)
        val inset = resources.getDimensionPixelSize(R.dimen.status_shortcut_inset)
        val gap = resources.getDimensionPixelSize(R.dimen.status_shortcut_gap)

        entries.forEachIndexed { index, entry ->
            val icon = android.widget.ImageView(this)
            val lp = android.widget.LinearLayout.LayoutParams(touch, touch)
            if (index > 0) lp.marginStart = gap
            icon.layoutParams = lp
            icon.setPadding(inset, inset, inset, inset)
            icon.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            icon.setImageDrawable(entry.icon)
            icon.background = getDrawable(R.drawable.bg_status_icon)
            icon.contentDescription = entry.label
            icon.isClickable = true
            icon.isFocusable = true
            icon.setOnClickListener { launchShortcut(entry) }
            box.addView(icon)
        }
    }

    private fun launchShortcut(entry: com.lf.launcher.apps.AppEntry) {
        val ok = runCatching { startActivity(entry.launchIntent) }.isSuccess
        if (!ok) {
            android.widget.Toast.makeText(
                this,
                "打不开 ${entry.label}，可能已被卸载",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            // 打不开就别继续挂着一个死图标
            renderShortcuts()
        }
    }

    /** 点三点时去哪儿：功能没开就去设置页开，开了但没选就直接进选择页 */
    private fun openShortcutEntry() {
        val target = if (StatusShortcuts.enabled()) {
            com.lf.launcher.settings.ShortcutPickerActivity::class.java
        } else {
            SettingsActivity::class.java
        }
        startActivity(Intent(this, target))
    }

    private fun applyWallpaper() {
        val drawable = runCatching { WallpaperManager.getInstance(this).drawable }.getOrNull()
        if (drawable != null) {
            binding.wallpaperBg.setImageDrawable(drawable)
        } else {
            binding.wallpaperBg.setBackgroundResource(R.drawable.bg_wallpaper_default)
        }
    }

    companion object {
        private const val TAG = "Launcher"

        /**
         * 「开机那几件抢前台的事」专用日志标签。
         *
         * 自动地图和开机自动播放音乐，两条流程都是「拉起别人的 App → 过一会儿回桌面」，
         * 它们互相踩的时候光看画面分不清谁干的。这个 tag 把每次决策和放弃原因都打出来，
         * `adb logcat -s LauncherFlow` 就能把整条时间线摊开。
         */
        private const val FLOW = "LauncherFlow"

        /**
         * 本次进程生命周期内是否已经自动拉过高德。
         *
         * **必须放进程级，不能放实例字段。** 日夜切换走的是 `Appearance.setMode()` →
         * `activity.recreate()`，Activity 重建后所有实例字段清零，`lastAutoMapAt`
         * 也跟着回到初始值 —— 于是"什么时候都能拉"的判定又成立了，每换一次主题
         * 就会把高德重新拽出来一次。
         *
         * 而这套流程本来只为开机那一下：高德被拉起来之后是常驻的，日常回桌面、
         * 换日夜都不需要再拉。真有需要，地图卡上还有「点这里启动一次」兜底。
         */
        @Volatile
        private var autoMapDoneThisProcess = false

        /** 同理：开机自动播放音乐，一次进程只做一次，别被 Activity 重建骗了 */
        @Volatile
        private var autoMusicDoneThisProcess = false

        /** 重建（日夜切换、内存回收）时用来恢复当前页 */
        private const val STATE_PAGE = "launcher_page"

        /** 首次启动批量申请运行时权限的请求码（避开 StatusTray 的 7001 那一档） */
        const val REQ_ALL = 8301

        /** 权限框刚消失后等多久再弹悬浮窗说明，别两个框挤在一起 */
        private const val OVERLAY_PROMPT_DELAY_MS = 900L

        /** 桌面回到前台后延迟多久拉起地图：先让它画完一帧，别切过去还是空的 */
        private const val AUTO_MAP_OPEN_DELAY_MS = 400L

        /** 地图起来后多久切回桌面 */
        private const val AUTO_MAP_BACK_MS = 2000L

        /** 拉起后多久还没把桌面切到后台，就认定地图没起来 */
        private const val AUTO_MAP_WATCHDOG_MS = 4000L

        /** 冷却时间：这段时间内反复回到桌面不会重复拉起（从设置页回来不该被打断） */
        private const val AUTO_MAP_COOLDOWN_MS = 15_000L

        /** 设置页关闭时置位，桌面回到前台后切回首页 —— 否则改完设置看到的是应用页，像没生效 */
        @Volatile
        var returnToHome = false

        /** 诊断页用：本次开机有没有拉过地图 / 音乐（只读） */
        val autoMapDone: Boolean get() = autoMapDoneThisProcess
        val autoMusicDone: Boolean get() = autoMusicDoneThisProcess
    }    private inner class PagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment =
            if (position == 0) HomeFragment() else AppListFragment()
    }
}
