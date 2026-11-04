package com.lf.launcher

import android.app.Application
import com.lf.launcher.diag.FlowLog
import com.lf.launcher.music.LastPlayed
import com.lf.launcher.settings.SettingsStore
import com.lf.launcher.ui.Appearance

/**
 * 进程入口。
 *
 * 只干一件事：把「跟随系统 / 固定日间 / 固定夜间」这个设置，在**任何 Activity 创建之前**
 * 同步给 AppCompatDelegate。晚于 Activity 创建再设，就会先按旧模式把界面画一遍再重建，
 * 车机上会看到明显的一次闪。
 */
class LauncherApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 最先装崩溃兜底：后面任何一步崩了都要兜得住（桌面崩了就是白屏，车主没法自救）
        CrashGuard.install(this)
        SettingsStore.init(this)
        // 流程日志：车机上没 adb，全靠诊断页把最近几百行摊开
        FlowLog.init(this)
        // 上次播放的歌：连不上播放器时音乐卡显示它，不再是空卡
        LastPlayed.init(this)
        // 任何界面创建之前先定好日夜，避免先按旧模式画一遍再重建
        Appearance.syncDelegate()
    }
}
