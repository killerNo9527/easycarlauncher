package com.lf.launcher.music

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import com.lf.launcher.LauncherActivity
import com.lf.launcher.diag.FlowLog
import com.lf.launcher.settings.Keys
import com.lf.launcher.settings.SettingsStore

/**
 * 双击音乐卡要干的事：**尽量静默地把音乐 App 叫起来，别让它抢前台**。
 *
 * 走两步：
 *
 *   1. 先 `startService` 它的 Media3 会话服务（[MusicController.startSilently]）。
 *      这条路上它的界面根本不会出现，桌面始终在前台 —— 这是车里最想要的形态：
 *      后台放着歌，屏上还停在导航。
 *   2. 服务被叫起来了不等于会话能用。**md3Music 就是反例**：服务能 start，但它把
 *      「恢复上次队列」放在界面 onCreate 里，光起服务只得到一个空会话，MediaController
 *      连不上（实测 1.4 秒后 connected=false）。
 *
 * 所以复查没过就退回打开界面，并让 [LauncherActivity.comeBackAfterMusic] 在
 * [BACK_DELAY_MS] 后把桌面请回前台；那段回桌面逻辑带重试，不会因为地图在收尾就放弃。
 *
 * ## 为什么记「连续失败次数」
 *
 * 对一个不支持的播放器，每次双击都白等 1.4 秒才弹界面，很难受。
 * 连续失败 [GIVE_UP_AFTER] 次就记住"它不行"，之后直接开界面；只要成功一次就清零。
 * 换播放器 / 清应用数据都会重置。
 */
object MusicLauncher {

    private const val TAG = "MusicLaunch"

    /** 兜底打开界面后多久回桌面：够它把首帧画出来，又不至于让人盯着别人的界面 */
    const val BACK_DELAY_MS = 1_000L

    /** 连续失败这么多次就认定"这个播放器不支持静默拉起" */
    private const val GIVE_UP_AFTER = 2

    /** 认定"不支持"之后，隔这么久再给一次机会（播放器可能升级后支持了） */
    private const val RETRY_AFTER_MS = 30L * 24 * 60 * 60 * 1000

    /** 复查节奏：先快后慢，支持的播放器能早一点确认 */
    private const val FIRST_CHECK_MS = 700L
    private const val SECOND_CHECK_MS = 1_400L

    private val main = Handler(Looper.getMainLooper())

    /**
     * @param anchor 用来跟界面存在性绑定（离屏就不弹 toast）
     * @param activity 桌面 Activity，兜底回桌面要用它
     */
    fun onDoubleTap(anchor: View, activity: Activity?) {
        if (activity == null || activity.isFinishing) return

        // ① 会话本来就在：什么都不用启动，缺的只是"开始播"这一下
        //    注意用 isSessionReady 而不是 snapshot().connected —— 后者在播放器被
        //    force-stop 之后可能还报"已连接"（见 MusicController 里的说明）
        val snap = MusicController.snapshot()
        if (MusicController.isSessionReady && snap.title.isNotBlank()) {
            FlowLog.i(TAG, "双击：会话已在（title=${snap.title}），直接播")
            if (!snap.isPlaying) MusicController.playPause()
            return
        }

        // ② 这播放器之前试过、不行：别再白等，直接开界面
        if (silentFailures() >= GIVE_UP_AFTER) {
            FlowLog.i(TAG, "双击：该播放器已认定不支持静默拉起，直接开界面")
            openForeground(anchor, activity)
            return
        }

        // ③ 试静默
        if (!MusicController.startSilently(activity)) {
            FlowLog.i(TAG, "双击：静默拉起发不出去，直接开界面")
            recordSilent(false)
            openForeground(anchor, activity)
            return
        }

        // ④ 分两次复查：0.7s 没成再等到 1.4s，谁先连上算谁赢
        main.postDelayed({ verify(anchor, activity, 1) }, FIRST_CHECK_MS)
    }

    private fun verify(anchor: View, activity: Activity?, attempt: Int) {
        if (activity == null || activity.isFinishing) return
        val now = MusicController.snapshot()
        if (MusicController.isSessionReady && now.title.isNotBlank()) {
            recordSilent(true)
            FlowLog.i(TAG, "双击：静默拉起成功（第 $attempt 次复查，title=${now.title}）")
            if (!now.isPlaying) MusicController.playPause()
            return
        }
        if (attempt == 1) {
            main.postDelayed({ verify(anchor, activity, 2) }, SECOND_CHECK_MS - FIRST_CHECK_MS)
            return
        }
        recordSilent(false)
        FlowLog.i(
            TAG,
            "双击：静默拉起后会话仍不可用（ready=${MusicController.isSessionReady} " +
                "title=${now.title}），改为打开界面",
        )
        openForeground(anchor, activity)
    }

    /** 兜底：正常打开音乐 App，然后由桌面在 [BACK_DELAY_MS] 后自己回来 */
    private fun openForeground(anchor: View, activity: Activity) {
        val ctx = activity.applicationContext
        val intent = runCatching {
            ctx.packageManager.getLaunchIntentForPackage(MusicController.MD3_PKG)
        }.getOrNull()
        if (intent == null) {
            toast(anchor, "没有找到音乐应用")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val ok = runCatching { ctx.startActivity(intent) }
            .onFailure { FlowLog.w(TAG, "打开音乐界面失败：$it") }
            .isSuccess
        if (!ok) {
            toast(anchor, "启动音乐失败")
            return
        }
        FlowLog.i(TAG, "双击：已打开音乐界面，${BACK_DELAY_MS}ms 后回桌面")
        (activity as? LauncherActivity)?.comeBackAfterMusic(BACK_DELAY_MS)
    }

    // ---------------------------------------------------------------- 静默能力记忆

    private fun silentFailures(): Int {
        val n = SettingsStore.getInt(Keys.MUSIC_SILENT_FAILS, 0)
        if (n < GIVE_UP_AFTER) return n
        // 上次失败过去够久了：当作没见过，重新试一次（播放器可能已经支持）
        val at = SettingsStore.getString(Keys.MUSIC_SILENT_AT, "").toLongOrNull() ?: 0L
        if (at > 0 && System.currentTimeMillis() - at < RETRY_AFTER_MS) return n
        FlowLog.i(TAG, "静默能力结论已过期（30 天），重新尝试")
        return 0
    }

    private fun recordSilent(ok: Boolean) {
        val cur = silentFailures()
        val next = if (ok) 0 else (cur + 1).coerceAtMost(GIVE_UP_AFTER)
        if (!ok) {
            SettingsStore.setString(Keys.MUSIC_SILENT_AT, System.currentTimeMillis().toString())
        }
        if (next != cur || ok) {
            SettingsStore.setInt(Keys.MUSIC_SILENT_FAILS, next)
            FlowLog.i(TAG, "静默能力计数 → $next（${if (ok) "成功清零" else "连续失败"}）")
        }
    }

    private fun toast(anchor: View, msg: String) {
        if (anchor.isAttachedToWindow) {
            Toast.makeText(anchor.context, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
