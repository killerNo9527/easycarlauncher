package com.lf.launcher.apps

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lf.launcher.R
import com.lf.launcher.databinding.FragmentAppListBinding
import com.lf.launcher.settings.SettingsActivity

class AppListFragment : Fragment(R.layout.fragment_app_list) {

    private var binding: FragmentAppListBinding? = null
    private val adapter = AppAdapter(
        onClick = { entry -> runCatching { startActivity(entry.launchIntent) } },
        onLongClick = { anchor, entry -> showAppMenu(anchor, entry) },
    )

    /**
     * 安装 / 卸载 / 更新 / 启用禁用 都会来一条包广播，收到就重扫一遍列表。
     *
     * 为什么必须监听：这一页原来只在 `onViewCreated` 里扫一次包名列表，装完新应用不重启桌面
     * 就永远看不到它 —— 而桌面是 HOME，可能几天都不重建。
     *
     * 为什么用广播而不是 `LauncherApps.registerCallback`：广播在 API 28 上直接可用、不需要
     * 额外服务绑定，而且我们对「变了就整表重扫」已经够快（几十毫秒），不需要增量 diff。
     */
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            scheduleRefresh()
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private val refreshTask = Runnable { reload() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val b = FragmentAppListBinding.inflate(inflater, container, false)
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = binding ?: return

        // 列数按屏宽自适应：每个格子约 140dp 宽，至少 3 列
        val widthDp = resources.configuration.screenWidthDp
        val spanCount = (widthDp / 140).coerceIn(3, 8)

        b.appGrid.layoutManager = GridLayoutManager(requireContext(), spanCount)
        b.appGrid.adapter = adapter
        b.appGrid.itemAnimator = null

        reload()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            // 带 data scheme 才会收到「包」相关的广播，漏了这条一条都收不到
            addDataScheme("package")
        }
        runCatching { requireContext().registerReceiver(packageReceiver, filter) }
        // 后台期间可能漏广播（进程被冻、广播被合并），回前台补扫一次
        scheduleRefresh()
    }

    override fun onStop() {
        runCatching { requireContext().unregisterReceiver(packageReceiver) }
        ui.removeCallbacks(refreshTask)
        super.onStop()
    }

    /** 一次安装会连着来好几条（ADDED + REPLACED），压一下再扫 */
    private fun scheduleRefresh() {
        ui.removeCallbacks(refreshTask)
        ui.postDelayed(refreshTask, 300)
    }

    private fun reload() {
        val b = binding ?: return
        val ctx = context ?: return

        // 设置没有独立启动图标，作为网格里的第一个条目出现（底部导航默认关闭后的唯一入口）
        val settingsEntry = AppEntry(
            label = "桌面设置",
            packageName = SETTINGS_ENTRY,
            icon = ContextCompat.getDrawable(ctx, R.drawable.ic_settings),
            launchIntent = Intent(ctx, SettingsActivity::class.java),
        )
        val apps = AppRepository.loadLaunchable(ctx)
        adapter.submit(listOf(settingsEntry) + apps)
        b.appEmpty.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
        // 装/卸应用时排查用：adb logcat -s AppList
        Log.d("AppList", "reload count=${apps.size}")
    }

    companion object {
        /** 伪包名，仅用于在网格里标识设置入口 */
        const val SETTINGS_ENTRY = "__settings__"
    }

    // ---------- 长按菜单 ----------

    /**
     * 长按图标弹出的菜单，对应系统原生的「应用信息 / 卸载」。
     *
     * 为什么自己画而不是用 `PopupMenu`：列表项是 140dp 宽的图标格，系统 PopupMenu 的字号
     * 跟应用主题走，在车机上偏小、点不准；这里固定 190dp 宽、17sp、每行 12dp 上下内边距，
     * 手指按得住。样式和均衡器弹窗保持同一套深色。
     *
     * 用 `ACTION_DELETE` 让系统弹卸载确认框，而不是自己去删：卸载是危险操作，
     * 确认这一步必须由系统给（也只有系统能真删）。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun showAppMenu(anchor: View, entry: AppEntry) {
        // 设置入口是伪条目，没有包名可查，长按不弹菜单
        if (entry.packageName == SETTINGS_ENTRY) return

        val ctx = anchor.context
        val density = ctx.resources.displayMetrics.density
        val dp: (Int) -> Int = { v -> (v * density).toInt() }

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_app_menu)
            val pad = dp(6)
            setPadding(pad, pad, pad, pad)
        }

        val popup = PopupWindow(
            box, dp(190), ViewGroup.LayoutParams.WRAP_CONTENT, true,
        ).apply {
            // 透明背景是必须的：不给的话 outside-touch 收不到，点外面关不掉
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(10).toFloat()
        }

        fun addItem(text: String, danger: Boolean, action: () -> Unit) {
            box.addView(menuRow(ctx, text, danger, dp) {
                popup.dismiss()
                action()
            })
        }

        addItem("应用信息", false) {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${entry.packageName}"),
            )
            runCatching { startActivity(intent) }
        }
        if (entry.canUninstall) {
            addItem("卸载", true) {
                val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${entry.packageName}"))
                runCatching { startActivity(intent) }
                    .onFailure { toast("无法卸载 ${entry.label}") }
            }
        }

        // 让菜单在图标正下方居中；贴右边缘时系统会把窗口挪回屏内
        val xoff = anchor.width / 2 - dp(190) / 2
        runCatching { popup.showAsDropDown(anchor, xoff, dp(4)) }
            .onFailure { popup.showAtLocation(anchor, Gravity.CENTER, 0, 0) }
        Log.d("AppList", "menu ${entry.packageName} canUninstall=${entry.canUninstall}")
    }

    private fun menuRow(
        ctx: Context,
        text: String,
        danger: Boolean,
        dp: (Int) -> Int,
        onClick: () -> Unit,
    ): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 17f
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(if (danger) 0xFFE8734A.toInt() else 0xFFF2F5F8.toInt())
        setBackgroundResource(R.drawable.bg_app_menu_item)
        val ph = dp(16)
        val pv = dp(13)
        setPadding(ph, pv, ph, pv)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun toast(msg: String) {
        val ctx = context ?: return
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        binding?.appGrid?.adapter = null
        binding = null
        super.onDestroyView()
    }

    private class AppAdapter(
        private val onClick: (AppEntry) -> Unit,
        private val onLongClick: (View, AppEntry) -> Unit,
    ) : RecyclerView.Adapter<AppAdapter.Holder>() {

        private val items = mutableListOf<AppEntry>()

        fun submit(list: List<AppEntry>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app, parent, false)
            return Holder(view, onClick, onLongClick)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

        class Holder(
            view: View,
            private val onClick: (AppEntry) -> Unit,
            private val onLongClick: (View, AppEntry) -> Unit,
        ) : RecyclerView.ViewHolder(view) {
            private val icon: ImageView = view.findViewById(R.id.app_icon)
            private val label: TextView = view.findViewById(R.id.app_label)
            private var entry: AppEntry? = null

            init {
                view.setOnClickListener { entry?.let(onClick) }
                view.setOnLongClickListener {
                    val e = entry ?: return@setOnLongClickListener false
                    // 车机全是手指点，给一次明确触感反馈，否则长按有没有生效只能靠眼睛
                    it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onLongClick(it, e)
                    true
                }
            }

            fun bind(item: AppEntry) {
                entry = item
                label.text = item.label
                icon.setImageDrawable(item.icon)
            }
        }
    }
}
