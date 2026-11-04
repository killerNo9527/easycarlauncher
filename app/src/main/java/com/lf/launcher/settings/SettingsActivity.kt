package com.lf.launcher.settings

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.lf.launcher.ui.Appearance
import com.lf.launcher.ui.Permissions
import com.lf.launcher.ui.SystemUi
import com.lf.launcher.ui.ThemedActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.lf.launcher.LauncherActivity
import com.lf.launcher.databinding.ActivitySettingsBinding
import com.lf.launcher.weather.WeatherLocator

/** 桌面的设置页。分组与条目对照原版 settings_* 的结构，去掉授权与注入相关项。 */
class SettingsActivity : ThemedActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var adapter: SettingsAdapter

    /** 建页时的昼夜模式；用来判断这次改动是否需要重建（偏好是适配器先写、后回调的） */
    private var modeAtBuild = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsStore.init(this)
        modeAtBuild = Appearance.mode()

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 和桌面用同一套系统栏策略：开启「使用系统栏」时设置页也显示系统两条栏，
        // 否则用户会被关在一个没有系统导航栏的页面里
        SystemUi.apply(this)
        SystemUi.attachContentInset(binding.root, withCustomPadding = false)

        adapter = SettingsAdapter(SettingsSpec.build(this)) { onSettingChanged() }
        binding.settingsList.layoutManager = LinearLayoutManager(this)
        binding.settingsList.adapter = adapter
        binding.settingsList.itemAnimator = null

        binding.btnBack.setOnClickListener { finish() }
    }

    /**
     * 任何一项改动后都过一遍 [Appearance.apply]：
     * 只有昼夜模式真的变了，AppCompat 才会重建界面；其余项是空操作。
     */
    private fun onSettingChanged() {
        val mode = Appearance.mode()
        // 昼夜模式改了就立刻重建（onCreate 会用新配置重新解析颜色）；
        // 其它项只是普通写入，刷新一下当前条目的显示值即可
        if (mode != modeAtBuild) {
            modeAtBuild = mode
            Appearance.setMode(this, mode)
            return
        }
        // 开关本身可能改的是「用不用系统栏」—— 要立刻生效，不能等退出再回来
        SystemUi.apply(this)
        SystemUi.refresh(binding.root)
        adapter.submit(SettingsSpec.build(this))
    }

    /** 子对话框里改了值（比如四边留白）后，刷新右侧显示值 */
    fun refreshItems() {
        adapter.submit(SettingsSpec.build(this))
    }

    // ------------------------------------------------------------ 定位权限

    /** 等权限结果的那段代码；同一时刻只会有一个（点开关或点「重新定位」） */
    private var afterPermission: ((Boolean) -> Unit)? = null

    /**
     * 有权限就直接回调，没有就弹系统授权框。
     *
     * 权限只在用户真的打开「城市跟随定位」时才申请 —— 桌面其它功能一个危险权限都不要，
     * 不该因为一个天气卡就让人装完先看三个权限弹窗。
     */
    fun ensureLocationPermission(after: (Boolean) -> Unit) {
        if (WeatherLocator.hasPermission(this)) {
            after(true)
            return
        }
        afterPermission = after
        requestPermissions(WeatherLocator.PERMISSIONS, REQ_LOCATION)
    }

    /**
     * 补授运行时权限（设置 → 权限 → 运行时权限）。
     *
     * 首次启动已经统一问过一次，这里是用户当时拒了之后的唯一入口 ——
     * 系统不会再自动弹，只能他自己进来点。
     */
    fun requestMissingPermissions() {
        val missing = Permissions.missingRuntime(this)
        if (missing.isEmpty()) {
            Toast.makeText(this, "已经全部授予了", Toast.LENGTH_SHORT).show()
            refreshItems()
            return
        }
        requestPermissions(missing.toTypedArray(), REQ_ALL)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_ALL -> {
                refreshItems()
                val missing = Permissions.missingRuntime(this)
                if (missing.isEmpty()) {
                    Toast.makeText(this, "权限已就绪", Toast.LENGTH_SHORT).show()
                    return
                }
                // 一条都弹不出来了（用户勾过"不再询问"）：系统页里手动开是唯一的路
                if (missing.all { Permissions.isBlocked(this, it) }) {
                    AlertDialog.Builder(this)
                        .setTitle("系统不再弹授权框了")
                        .setMessage(
                            "「${Permissions.describe(missing)}」被设为不再询问，只能到应用信息页里手动打开。",
                        )
                        .setPositiveButton("去设置") { _, _ -> Permissions.openAppDetails(this) }
                        .setNegativeButton("取消", null)
                        .show()
                } else {
                    Toast.makeText(
                        this,
                        "还差「${Permissions.describe(missing)}」",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }

            REQ_LOCATION -> {
                val ok = WeatherLocator.hasPermission(this)
                val cb = afterPermission
                afterPermission = null
                // 权限回来之后刷新列表：开关状态/摘要文案可能已经变了
                refreshItems()
                cb?.invoke(ok)
            }
        }
    }

    private companion object {
        const val REQ_LOCATION = 7101

        /** 与 LauncherActivity.REQ_ALL 不同值：两个页面各自处理自己的回调 */
        const val REQ_ALL = 7301
    }

    override fun onPause() {
        super.onPause()
        // 只有真正关闭设置页才让桌面回首页；打开选择器时不算
        if (isFinishing) LauncherActivity.returnToHome = true
    }

    /** 从「选择悬浮地图」等子页面返回时，重建条目以刷新右侧显示值 */
    override fun onResume() {
        super.onResume()
        SystemUi.apply(this)
        SystemUi.refresh(binding.root)
        adapter.submit(SettingsSpec.build(this))
    }

}
