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
import com.lf.launcher.home.MapAppLauncher
import com.lf.launcher.music.MusicController
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

    /** 上次自动拉起的时间，用于冷却 */
    private var lastAutoMapAt = 0L

    /** 桌面自己是否在前台：自动拉起地图前要再确认一次，防止抢走用户刚打开的页面 */
    private var resumed = false

    /** 2 秒倒计时是否已经排上（由 onPause 排，保证是"地图真的在前面"才开始算） */
    private var autoMapBackScheduled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        outState.putInt(STATE_PAGE, binding.viewPager.currentItem)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
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
        // 自动拉起地图的流程：桌面被切到后台 = 地图已经盖上来了，2 秒从这一刻开始算
        if (autoMapPending && !autoMapBackScheduled) {
            autoMapBackScheduled = true
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
        super.onDestroy()
    }

    /** 桌面里按返回不退出，只回首页 */
    @Deprecated("车机桌面不应退出")
    override fun onBackPressed() {
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
        if (suppressAutoMap) {
            // 是刚才那次自动流程把自己切回来的：吃掉标记，不再拉起
            suppressAutoMap = false
            return
        }
        val now = SystemClock.elapsedRealtime()
        // 从自己的二级页回来也重新计时：刚改完设置就弹地图会让人以为点错了
        if (skip) {
            lastAutoMapAt = now
            return
        }
        if (now - lastAutoMapAt < AUTO_MAP_COOLDOWN_MS) return
        lastAutoMapAt = now
        autoMapPending = true
        autoMapBackScheduled = false
        // 先让桌面画完一帧再拉起：否则切过去时桌面上还是空的
        handler.postDelayed(autoMapLaunch, AUTO_MAP_OPEN_DELAY_MS)
    }

    private val autoMapLaunch = Runnable {
        if (!autoMapPending) return@Runnable
        // 这 400ms 里如果用户已经点了别的（比如进了设置页），就别抢前台了
        if (!resumed) {
            autoMapPending = false
            return@Runnable
        }
        if (!MapAppLauncher.launchFullscreen(this)) {
            autoMapPending = false
            return@Runnable
        }
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
        if (!autoMapPending) return@Runnable
        autoMapPending = false
        autoMapBackScheduled = false
        suppressAutoMap = true
        // 自己是 singleTask：这个 Intent 不会新建实例，只是把桌面所在的任务提到前台
        runCatching {
            startActivity(
                Intent(this, LauncherActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
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
    private val musicAutoStartDelayWithMap = 6_000L

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
        handler.postDelayed(
            musicAutoStart,
            if (autoMapPending) musicAutoStartDelayWithMap else musicAutoStartDelayNoMap,
        )
    }

    /**
     * 真正去拉音乐的那一下。
     *
     * 桌面不在前台就先放手 —— 说明地图（或别的东西）还盖着，等下次 onResume 重排。
     * 这里不自己重试，否则万一前台一直不是自己，就变成无限循环了。
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
            if (!resumed) return
            autoMusicTried = true
            if (!MusicController.autoStart()) return
            handler.postDelayed(musicBack, musicBackDelayMs)
        }
    }

    /**
     * 音乐盖上来之后把自己请回前台。
     *
     * [resumed] 为 false = 已经不在前台，开机后这一瞬只可能是刚拉起的音乐，
     * 直接切回来；为 true = 音乐还没起来（冷启动慢），那就什么都不用做，
     * 它会在后台自己走完初始化并开始播放。
     */
    private val musicBack = Runnable {
        if (resumed) return@Runnable
        runCatching {
            startActivity(
                Intent(this, LauncherActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
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
    }    private inner class PagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment =
            if (position == 0) HomeFragment() else AppListFragment()
    }
}
