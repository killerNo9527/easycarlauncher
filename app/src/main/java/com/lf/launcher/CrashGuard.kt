package com.lf.launcher

import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃兜底。
 *
 * ## 为什么桌面必须自己做这件事
 *
 * 这是 HOME：崩在启动路径上就是**白屏**，车主连设置都进不去，除了拿电脑重装没别的办法。
 * 而车载环境恰恰是最不该出这种事的地方。
 *
 * ## 三件事
 *
 * 1. **接住崩溃**：记下时间、异常摘要，然后把进程交给系统处理（自己不吞异常，
 *    吞了会留下一个状态不确定的进程，比崩了还难查）。
 * 2. **排一次自动重启**：有些车机不会自动把 HOME 拉回来，用 AlarmManager 补一刀。
 * 3. **连续崩溃进安全模式**：60 秒内崩 3 次，说明是真的坏了，再这么重启下去就是
 *    无限崩溃循环。这时下一次启动只画一个极简页（不加载卡片、地图、音乐），
 *    给「重启桌面」和「切换到其他桌面」两个出口。
 *
 * 安全模式的标记存在**独立**的 SharedPreferences 里，不依赖 [com.lf.launcher.settings.SettingsStore]——
 * 万一崩就是因为配置读取出问题，也还能进得来。
 */
object CrashGuard {

    private const val TAG = "CrashGuard"
    private const val PREFS = "crash_guard"
    private const val KEY_TIMES = "crash_times"
    private const val KEY_SAFE = "safe_mode"
    private const val KEY_LAST = "last_crash"

    /** 在这个时间窗内崩够 [THRESHOLD] 次就认为救不回来了 */
    private const val WINDOW_MS = 60_000L

    /** 连续几次进安全模式 */
    private const val THRESHOLD = 3

    /** 崩溃后多久把桌面拉回来（给系统一次自己重启的机会，别抢） */
    private const val RESTART_DELAY_MS = 1_200L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(app, thread, error) }
            runCatching { scheduleRestart(app) }
            // 交还给系统（或原来的 handler）：不吞异常，让进程干净地死掉
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    private fun record(app: Application, thread: Thread, error: Throwable) {
        val p = prefs(app)
        val now = SystemClock.elapsedRealtime()
        val recent = p.getString(KEY_TIMES, "")
            .orEmpty()
            .split(',')
            .mapNotNull { it.trim().toLongOrNull() }
            .filter { now - it < WINDOW_MS }
            .plus(now)
            .takeLast(10)

        val count = recent.size
        val edit = p.edit().putString(KEY_TIMES, recent.joinToString(","))
        if (count >= THRESHOLD) {
            edit.putBoolean(KEY_SAFE, true)
        }
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        edit.putString(KEY_LAST, "$stamp  ${error.javaClass.simpleName}: ${error.message}")
        edit.apply()

        Log.e(TAG, "捕获崩溃（${WINDOW_MS / 1000}秒内第 $count 次）", error)
        // 除了 logcat 再落一份文件：车主没法连 adb，这份是事后唯一的线索
        runCatching {
            app.filesDir.resolve("crash.log").appendText(
                "$stamp [$thread] ${error.javaClass.name}: ${error.message}\n" +
                    error.stackTrace.take(15).joinToString("\n") { "    at $it" } + "\n\n",
            )
        }
    }

    /**
     * 自觉重启。
     *
     * 用 AlarmManager 而不是直接 startActivity：异常处理器里进程马上就要没了，
     * 这时候启动 Activity 大概率白做。闹钟是交给系统闹钟服务排的，进程死了也有效。
     */
    private fun scheduleRestart(app: Application) {
        val intent = Intent(app, LauncherActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val flags = if (Build.VERSION.SDK_INT >= 23) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getActivity(app, 0, intent, flags)
        val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching {
            am.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + RESTART_DELAY_MS,
                pi,
            )
            Log.i(TAG, "${RESTART_DELAY_MS}ms 后自动拉起桌面")
        }
    }

    /** 该不该进安全模式（崩溃时已经写好的标记） */
    fun isSafeMode(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SAFE, false)

    /** 最近一次崩溃的摘要，给安全模式页显示 */
    fun lastCrash(ctx: Context): String = prefs(ctx).getString(KEY_LAST, "").orEmpty()

    /** 退出安全模式：清标记和崩溃记录，让下次启动正常跑 */
    fun exitSafeMode(ctx: Context) {
        prefs(ctx).edit().remove(KEY_SAFE).remove(KEY_TIMES).apply()
        Log.i(TAG, "退出安全模式")
    }

    /**
     * 把桌面让给别人。
     *
     * 先清掉自己的 HOME 偏好，再发一个 HOME intent —— 系统里有第二个桌面时会弹选择框，
     * 用户点一下就切过去了。清不掉（非默认桌面 / ROM 限制）也没关系，选择框照样会弹。
     */
    fun switchToOtherLauncher(activity: Activity) {
        val pm = activity.packageManager
        runCatching { pm.clearPackagePreferredActivities(activity.packageName) }
            .onFailure { Log.w(TAG, "清 HOME 偏好失败：$it") }
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { activity.startActivity(home) }
            .onFailure { Log.w(TAG, "打开桌面选择器失败：$it") }
    }

    /** 系统里除了自己还有别的桌面吗（没有的话那个按钮就别显示了，点了也白点） */
    fun hasOtherLauncher(activity: Activity): Boolean {
        val homes = runCatching {
            activity.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                0,
            )
        }.getOrDefault(emptyList())
        return homes.any {
            val pkg = it.activityInfo?.packageName
            pkg != null && pkg != activity.packageName && pkg != "com.android.settings"
        }
    }

    /**
     * 安全模式界面。
     *
     * 全程用代码搭，不 inflate 布局文件——万一崩在布局本身，这里再崩就真没救了。
     */
    fun buildSafeView(activity: Activity, onRestart: () -> Unit): View {
        val pad = dp(activity, 56)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0E1013"))
            setPadding(pad, pad, pad, pad)
        }

        root.addView(
            TextView(activity).apply {
                text = "桌面启动异常"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 46f)
                gravity = Gravity.CENTER
            },
        )

        root.addView(
            TextView(activity).apply {
                text = "连续崩溃 ${THRESHOLD} 次，已进入安全模式。\n" +
                    "这个界面不加载地图、音乐和卡片，所以不会再崩。"
                setTextColor(Color.parseColor("#B6BEC9"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
                gravity = Gravity.CENTER
                setPadding(0, dp(activity, 20), 0, dp(activity, 10))
            },
        )

        val crash = lastCrash(activity)
        if (crash.isNotEmpty()) {
            root.addView(
                TextView(activity).apply {
                    text = "最近一次：$crash"
                    setTextColor(Color.parseColor("#7A838F"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, dp(activity, 44))
                },
            )
        }

        root.addView(
            makeButton(activity, "重启桌面", primary = true) {
                exitSafeMode(activity)
                onRestart()
            },
        )

        if (hasOtherLauncher(activity)) {
            root.addView(
                makeButton(activity, "切换到其他桌面", primary = false) {
                    switchToOtherLauncher(activity)
                },
            )
        }

        return root
    }

    private fun makeButton(activity: Activity, label: String, primary: Boolean, onClick: () -> Unit) =
        Button(activity).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(if (primary) Color.parseColor("#0E1013") else Color.parseColor("#E6EAF0"))
            background = GradientDrawable().apply {
                cornerRadius = dp(activity, 16).toFloat()
                setColor(Color.parseColor(if (primary) "#4C8DFF" else "#232830"))
            }
            val lp = LinearLayout.LayoutParams(dp(activity, 460), dp(activity, 76))
            lp.topMargin = dp(activity, 16)
            layoutParams = lp
            setOnClickListener { onClick() }
        }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
