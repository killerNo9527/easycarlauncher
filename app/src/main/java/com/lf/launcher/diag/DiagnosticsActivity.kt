package com.lf.launcher.diag

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.lf.launcher.CrashGuard
import com.lf.launcher.LauncherActivity
import com.lf.launcher.R
import com.lf.launcher.home.MapAppLauncher
import com.lf.launcher.map.AmapPlusController
import com.lf.launcher.map.AppAlive
import com.lf.launcher.music.MusicController
import com.lf.launcher.ui.Permissions
import com.lf.launcher.ui.SystemUi
import com.lf.launcher.ui.ThemedActivity

/**
 * 诊断页。
 *
 * ## 为什么要有
 *
 * 车机上看不到 logcat。以前出问题只能靠车主口述"地图不出来""音乐连不上"，
 * 我再去猜。这个页面把三件事摊开：
 *
 * 1. **当前状态** —— 地图进程活没活、悬浮窗挂没挂、音乐连没连、权限齐不齐
 * 2. **本次开机的流程决策** —— 自动拉地图/音乐到底跑没跑（很多"没生效"其实是流程没触发）
 * 3. **最近几百行流程日志** —— 带时间戳，能看出先后顺序
 *
 * 全部内容可以一键复制，车主发一段文本过来就够定位，不用连电脑。
 */
class DiagnosticsActivity : ThemedActivity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SystemUi.apply(this)
        setContentView(buildView())
        SystemUi.attachContentInset(findViewById(android.R.id.content), withCustomPadding = false)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun buildView(): View {
        val pad = dp(36)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.bg_base))
            setPadding(pad, dp(18), pad, dp(12))
        }

        // ---- 顶部标题栏 ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(this).apply {
                text = "诊断"
                setTextColor(color(R.color.text_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            },
        )
        header.addView(
            View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            },
        )
        header.addView(navButton("复制全部") { copyAll() })
        header.addView(navButton("清空日志") {
            FlowLog.clear()
            refresh()
            Toast.makeText(this, "流程日志已清空", Toast.LENGTH_SHORT).show()
        })
        header.addView(navButton("返回") { finish() })
        root.addView(header)

        // ---- 状态区 ----
        statusView = TextView(this).apply {
            setTextColor(color(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(5).toFloat(), 1f)
            setPadding(0, dp(14), 0, dp(10))
        }
        root.addView(statusView)

        // ---- 日志区 ----
        root.addView(
            TextView(this).apply {
                text = "流程日志（最近 200 行）"
                setTextColor(color(R.color.text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(0, dp(4), 0, dp(6))
            },
        )
        logView = TextView(this).apply {
            setTextColor(color(R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextIsSelectable(true)
            setPadding(0, 0, 0, dp(24))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(
                logView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        // 宽度必须 MATCH_PARENT + 高度给 weight：宽度写 0 会算出 0 宽，
        // 日志区直接看不见（第一次就是这么错的）
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
            ).also { it.weight = 1f },
        )
        return root
    }

    private fun navButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(color(R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setBackgroundResource(R.drawable.bg_nav_item)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.marginStart = dp(10) }
        }

    private fun refresh() {
        statusView.text = buildStatus()
        val lines = FlowLog.tail(200)
        logView.text = if (lines.isEmpty()) "（还没有流程日志）" else lines.joinToString("\n")
    }

    /**
     * 状态摘要。
     *
     * 每一条都对应一个"以前只能靠猜"的问题：地图进程活没活、悬浮窗挂了没、
     * 流程跑没跑、权限在不在。写清楚"无法判断"的情况 —— 这台设备看不到别的进程，
     * 那是系统限制，不是桌面坏了。
     */
    private fun buildStatus(): String {
        val sb = StringBuilder()

        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrDefault("?")
        val code = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionCode
        }.getOrDefault(0)
        val dm = resources.displayMetrics
        sb.appendLine("== 运行环境 ==")
        sb.appendLine("版本 $version ($code)")
        sb.appendLine("系统 Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("屏幕 ${dm.widthPixels}×${dm.heightPixels} · ${dm.densityDpi}dpi")

        // ---- 地图 ----
        sb.appendLine()
        sb.appendLine("== 地图 ==")
        val pkg = MapAppLauncher.selectedPackage()
        if (pkg.isEmpty()) {
            sb.appendLine("应用 未选择（地图卡会是空态）")
        } else {
            sb.appendLine("应用 ${MapAppLauncher.selectedLabel(this)} ($pkg)")
            sb.appendLine(
                "进程 " + when (AppAlive.isRunning(this, pkg)) {
                    true -> "存活"
                    false -> "已退出"
                    null -> "无法判断（系统限制，看不到其它应用进程）"
                },
            )
        }
        sb.appendLine(
            "悬浮窗 " + if (AmapPlusController.isShown()) {
                "显示中 ${AmapPlusController.currentRect() ?: ""}"
            } else {
                "未显示"
            },
        )
        sb.appendLine("协议 ${AmapPlusController.summary(this)}")
        sb.appendLine(
            "本次开机 " +
                (if (LauncherActivity.autoMapDone) "已自动拉起过" else "尚未自动拉起"),
        )

        // ---- 音乐 ----
        val music = MusicController.snapshot()
        sb.appendLine()
        sb.appendLine("== 音乐 ==")
        sb.appendLine(
            "连接 " + when {
                music.connected -> "已连接"
                music.error.isNotBlank() -> "未连接（${music.error}）"
                else -> "未连接（播放器还没建会话）"
            },
        )
        sb.appendLine(
            "当前 " + if (music.title.isBlank()) {
                "—"
            } else {
                "${music.title} · ${music.artist}"
            },
        )
        sb.appendLine("歌词 " + if (music.hasLyric) "${music.lyricLineCount} 行" else "未获取")
        sb.appendLine(
            "本次开机 " + (if (LauncherActivity.autoMusicDone) "已自动拉起过" else "尚未自动拉起"),
        )

        // ---- 权限 ----
        sb.appendLine()
        sb.appendLine("== 权限 ==")
        val missing = Permissions.missingRuntime(this)
        sb.appendLine("运行时 " + if (missing.isEmpty()) "已全部授予" else "缺 ${Permissions.describe(missing)}")
        sb.appendLine("悬浮窗 " + if (Permissions.overlayGranted(this)) "已开启" else "未开启")

        // ---- 崩溃 ----
        sb.appendLine()
        sb.appendLine("== 崩溃 ==")
        sb.appendLine("安全模式 " + if (CrashGuard.isSafeMode(this)) "是（连续崩溃后进入）" else "否")
        val crash = CrashGuard.lastCrash(this)
        sb.appendLine("最近一次 " + crash.ifBlank { "—" })

        return sb.toString()
    }

    private fun copyAll() {
        val text = statusView.text.toString() + "\n\n" + logView.text.toString()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (cm == null) {
            Toast.makeText(this, "系统没有剪贴板服务", Toast.LENGTH_SHORT).show()
            return
        }
        cm.setPrimaryClip(ClipData.newPlainText("桌面诊断", text))
        Toast.makeText(this, "已复制，可粘贴到聊天窗口发我", Toast.LENGTH_SHORT).show()
    }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
