package com.lf.launcher.ui

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import com.lf.launcher.R

/**
 * 顶部状态栏右侧的「信号 / Wi-Fi / 蓝牙」三个真控件。
 *
 * ## 为什么要有这个类
 *
 * 上一版状态栏右边是空的，图标画上去也只是一张图 —— 点不动、也不反映真实状态。
 * 这里把三个图标各自接到系统 API 上：读状态、听广播、点一下真的改系统设置。
 *
 * ## 每一格能做什么
 *
 * | 图标 | 单击 | 长按 | 状态显示 |
 * |---|---|---|---|
 * | 信号 | 打开「无线和网络」设置（没有权限时先申请读取电话状态） | — | 真实 4 档信号格 |
 * | Wi-Fi | 开 / 关 | Wi-Fi 设置页 | 已连接 / 已开启未连 / 已关闭 |
 * | 蓝牙 | 开 / 关 | 蓝牙设置页 | 已连接 / 已开启未连 / 已关闭 |
 *
 * ## 几个必须记住的坑
 *
 * 1. **蓝牙 / Wi-Fi 开关都需要「normal 权限」**（`BLUETOOTH_ADMIN` / `CHANGE_WIFI_STATE`），
 *    装包即得，不用弹框；但系统照样可能拒绝（有的车机把开关锁在设置里），
 *    `enable()` / `setWifiEnabled()` 返回 false 时这里不装作成功，而是直接把你送到系统设置页。
 * 2. **信号格必须 `READ_PHONE_STATE`**（dangerous）。桌面是开机自启的 HOME，
 *    不能一启动就弹权限框，所以只在用户点信号图标时才申请一次；没授权就画成灰色，
 *    不拿"满格"糊弄。
 * 3. **广播要加 `package` 之外的 action 才收得到**：`RSSI_CHANGED_ACTION` 在清单里注册已失效，
 *    这里全部用运行时注册（随 onResume/onPause 起停），所以没问题。
 * 4. 三个图标的刷新都做了"状态没变就跳过"，因为 Wi-Fi 的 RSSI 广播来得非常勤，
 *    不加判断会一直 `setImageResource` + `invalidate`。
 */
class StatusTray(
    private val activity: Activity,
    root: View,
) {

    private val signalView: SignalBarsView = root.findViewById(R.id.status_signal)
    private val wifiView: ImageView = root.findViewById(R.id.status_wifi)
    private val btView: ImageView = root.findViewById(R.id.status_bt)

    private val appContext: Context = activity.applicationContext
    private val wifiManager: WifiManager? =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val telephony: TelephonyManager? =
        appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private val bluetooth: BluetoothAdapter? =
        runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()

    private val hasTelephony: Boolean =
        activity.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    private var registered = false

    /** 状态栏被设置项关掉时不用听广播 */
    private var barVisible = true

    /** 上一次渲染的状态，-1 = 还没渲染过 */
    private var lastWifi = -1
    private var lastBluetooth = -1
    private var lastSignal = -1

    /** 防抖：见 [allowToggle] */
    private var lastWifiToggleAt = 0L
    private var lastBtToggleAt = 0L

    private val phoneListener = object : PhoneStateListener() {
        override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
            val level = signalStrength?.level ?: 0
            applySignal(level, available = level > 0 || hasService())
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiManager.WIFI_STATE_CHANGED_ACTION,
                WifiManager.NETWORK_STATE_CHANGED_ACTION,
                WifiManager.RSSI_CHANGED_ACTION -> renderWifi()

                BluetoothAdapter.ACTION_STATE_CHANGED,
                BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> renderBluetooth()
            }
        }
    }

    init {
        signalView.setOnClickListener { onSignalClick() }

        wifiView.setOnClickListener { toggleWifi() }
        wifiView.setOnLongClickListener {
            openSystemSettings(Settings.ACTION_WIFI_SETTINGS)
            true
        }

        btView.setOnClickListener { toggleBluetooth() }
        btView.setOnLongClickListener {
            openSystemSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
            true
        }
    }

    /** 桌面回到前台：注册广播并立刻刷一次（离开期间系统状态可能变了） */
    fun start() {
        if (!barVisible) return
        if (!registered) {
            registered = true
            val filter = IntentFilter().apply {
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                addAction(WifiManager.RSSI_CHANGED_ACTION)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            // 注册失败（极少见）不该把桌面拖崩，退化成"只在 start 时刷一次"
            runCatching { activity.registerReceiver(receiver, filter) }
                .onFailure { Log.w(TAG, "registerReceiver failed: $it") }
            listenSignal(true)
        }
        renderWifi(force = true)
        renderBluetooth(force = true)
        renderSignal(force = true)
    }

    /** 桌面离开前台：注销，别在后台白耗电 */
    fun stop() {
        if (!registered) return
        registered = false
        runCatching { activity.unregisterReceiver(receiver) }
            .onFailure { Log.w(TAG, "unregisterReceiver failed: $it") }
        listenSignal(false)
    }

    /** 设置里关掉状态栏时，连广播一起停 */
    fun setBarVisible(visible: Boolean) {
        if (visible == barVisible) return
        barVisible = visible
        if (visible) start() else stop()
    }

    /** 用户在信号图标上授权之后回调 */
    fun onPhonePermissionResult(granted: Boolean) {
        if (granted) {
            listenSignal(true)
            renderSignal(force = true)
            toast("已允许读取通话状态，信号格已启用")
        } else {
            toast("未授权，信号格保持灰色")
        }
    }

    // ---------------------------------------------------------------- Wi-Fi

    private fun renderWifi(force: Boolean = false) {
        val wm = wifiManager
        val on = wm != null && runCatching { wm.isWifiEnabled }.getOrDefault(false)
        val linked = on && runCatching { isWifiLinked(wm!!) }.getOrDefault(false)

        val state = when {
            !on -> STATE_OFF
            linked -> STATE_LINKED
            else -> STATE_ON
        }
        if (!force && state == lastWifi) return
        lastWifi = state
        Log.i(TAG, "wifi state=${stateName(state)}")

        // 三态三张图：关 = 浅灰本体 + 红叉；开但没连 = 中灰 + 右下角「?」；已连 = 深色加粗。
        // 明暗差别做在矢量图的颜色里，不再调 View 的 alpha —— 红叉和图标本体在同一个
        // ImageView 里，alpha 会把红叉一起压淡，反而看不出「这格是关的」。
        wifiView.setImageResource(
            when (state) {
                STATE_LINKED -> R.drawable.ic_status_wifi_linked
                STATE_ON -> R.drawable.ic_status_wifi_ask
                else -> R.drawable.ic_status_wifi_off
            }
        )
        wifiView.contentDescription = when (state) {
            STATE_LINKED -> "Wi-Fi 已连接，点击关闭"
            STATE_ON -> "Wi-Fi 已开启但没有连接网络，点击关闭"
            else -> "Wi-Fi 已关闭，点击开启"
        }
    }

    @Suppress("DEPRECATION")
    private fun isWifiLinked(wm: WifiManager): Boolean = wm.connectionInfo.networkId != -1

    private fun toggleWifi() {
        val wm = wifiManager
        if (wm == null) {
            toast("本机没有 Wi-Fi 模块")
            return
        }
        // 车机触摸屏容易一次按下判成两次，双击的第一个事件必须在 700ms 内丢掉，
        // 否则「开」和「关」会互相抵消（用户看到的就是"点了没反应"或"点了反而关了"）
        if (!allowToggle(TOGGLE_WIFI)) return
        // 正在切换中再点一次只会把状态机搞乱，直接忽略
        if (wm.wifiState == WifiManager.WIFI_STATE_ENABLING ||
            wm.wifiState == WifiManager.WIFI_STATE_DISABLING
        ) {
            toast("Wi-Fi 正在切换，稍等")
            return
        }
        Log.i(TAG, "tap wifi current=${wm.isWifiEnabled}")
        val target = !wm.isWifiEnabled
        // 有的车机把 Wi-Fi 开关锁在系统设置里：这里拿到 false 就说明改不了，
        // 直接把人送到系统页面，而不是弹一句"已开启"然后图标一动不动
        val ok = runCatching { wm.setWifiEnabled(target) }.getOrDefault(false)
        if (!ok) {
            toast("系统不允许从这里改 Wi-Fi，已打开 Wi-Fi 设置")
            openSystemSettings(Settings.ACTION_WIFI_SETTINGS)
            return
        }
        toast(if (target) "正在开启 Wi-Fi…" else "正在关闭 Wi-Fi…")
    }

    // -------------------------------------------------------------- Bluetooth

    private fun renderBluetooth(force: Boolean = false) {
        val adapter = bluetooth
        val on = adapter?.isEnabled == true
        val linked = on && runCatching { isBtLinked(adapter!!) }.getOrDefault(false)

        val state = when {
            !on -> STATE_OFF
            linked -> STATE_LINKED
            else -> STATE_ON
        }
        if (!force && state == lastBluetooth) return
        lastBluetooth = state
        Log.i(TAG, "bt state=${stateName(state)}")

        btView.setImageResource(
            when (state) {
                STATE_LINKED -> R.drawable.ic_status_bt_linked
                STATE_ON -> R.drawable.ic_status_bt_ask
                else -> R.drawable.ic_status_bt_off
            }
        )
        btView.contentDescription = when (state) {
            STATE_LINKED -> "蓝牙已连接，点击关闭"
            STATE_ON -> "蓝牙已开启但没有连接设备，点击关闭"
            else -> "蓝牙已关闭，点击开启"
        }
    }

    /** 任意一路（耳机 / A2DP / BLE）连上就算"已连接" */
    private fun isBtLinked(adapter: BluetoothAdapter): Boolean {
        val profiles = intArrayOf(
            BluetoothProfile.HEADSET,
            BluetoothProfile.A2DP,
            BluetoothProfile.GATT,
        )
        return profiles.any { profile ->
            runCatching {
                adapter.getProfileConnectionState(profile) == BluetoothProfile.STATE_CONNECTED
            }.getOrDefault(false)
        }
    }

    private fun toggleBluetooth() {
        val adapter = bluetooth
        if (adapter == null) {
            toast("本机没有蓝牙模块")
            return
        }
        Log.i(TAG, "tap bt current=${adapter.isEnabled}")
        if (!allowToggle(TOGGLE_BT)) return
        if (adapter.state == BluetoothAdapter.STATE_TURNING_ON ||
            adapter.state == BluetoothAdapter.STATE_TURNING_OFF
        ) {
            toast("蓝牙正在切换，稍等")
            return
        }
        val target = !adapter.isEnabled
        val ok = runCatching {
            if (target) adapter.enable() else adapter.disable()
        }.getOrDefault(false)
        if (!ok) {
            toast("系统不允许从这里改蓝牙，已打开蓝牙设置")
            openSystemSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
            return
        }
        toast(if (target) "正在开启蓝牙…" else "正在关闭蓝牙…")
    }

    // ----------------------------------------------------------------- 信号

    private fun renderSignal(force: Boolean = false) {
        val tm = telephony
        if (!hasTelephony || tm == null) {
            applySignal(0, available = false, force = force)
            return
        }
        if (!hasPhonePermission()) {
            // 没权限就是读不到，画灰色比画满格诚实
            applySignal(0, available = false, force = force)
            return
        }
        val level = runCatching { tm.signalStrength?.level }.getOrNull()
        if (level == null) {
            applySignal(0, available = false, force = force)
        } else {
            applySignal(level, available = true, force = force)
        }
    }

    private fun applySignal(level: Int, available: Boolean, force: Boolean = false) {
        val state = level * 2 + if (available) 1 else 0
        if (!force && state == lastSignal) return
        lastSignal = state
        Log.i(TAG, "signal level=$level available=$available")
        signalView.setSignal(level, available)
        signalView.contentDescription = when {
            !available && !hasPhonePermission() -> "信号不可用，点击申请权限或打开网络设置"
            !available -> "无蜂窝网络，点击打开网络设置"
            level == 0 -> "信号很弱，点击打开网络设置"
            else -> "信号 $level 格（共 4 格），点击打开网络设置"
        }
    }

    private fun onSignalClick() {
        if (!hasTelephony) {
            toast("本机没有蜂窝网络模块")
            openSystemSettings(Settings.ACTION_WIRELESS_SETTINGS)
            return
        }
        if (!hasPhonePermission()) {
            toast("显示信号格需要「电话」权限，允许后即可读取")
            runCatching {
                activity.requestPermissions(
                    arrayOf(Manifest.permission.READ_PHONE_STATE),
                    REQ_PHONE,
                )
            }.onFailure { Log.w(TAG, "requestPermissions failed: $it") }
            return
        }
        openSystemSettings(Settings.ACTION_WIRELESS_SETTINGS)
    }

    private fun hasPhonePermission(): Boolean =
        activity.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun listenSignal(on: Boolean) {
        val tm = telephony ?: return
        if (on && !hasPhonePermission()) return
        runCatching {
            tm.listen(
                phoneListener,
                if (on) PhoneStateListener.LISTEN_SIGNAL_STRENGTHS else PhoneStateListener.LISTEN_NONE,
            )
        }.onFailure { Log.w(TAG, "listen signal failed: $it") }
    }

    /** 有服务（能注册上网络）但信号极弱时，别把 0 格画成"没有模块" */
    private fun hasService(): Boolean = runCatching {
        telephony?.serviceState?.state == android.telephony.ServiceState.STATE_IN_SERVICE
    }.getOrDefault(false)

    // ----------------------------------------------------------------- 其它

    /**
     * 同一个图标 700ms 内只接受一次点击。
     *
     * 为什么要这个：实测这台模拟器上点一次 Wi-Fi 图标，`WifiService` 会在几秒内收到
     * **两次** `setWifiEnabled`（真机上双击手势也很容易连发两次 DOWN/UP），
     * 结果是"点了开又马上被关掉"。防抖放在这一层，UI 和系统状态就不会打架。
     */
    private fun allowToggle(which: Int): Boolean {
        val now = SystemClock.elapsedRealtime()
        val last = if (which == TOGGLE_WIFI) lastWifiToggleAt else lastBtToggleAt
        if (now - last < TOGGLE_GUARD_MS) {
            Log.i(TAG, "toggle ignored (debounce, $which)")
            return false
        }
        if (which == TOGGLE_WIFI) lastWifiToggleAt = now else lastBtToggleAt = now
        return true
    }

    private fun stateName(state: Int): String = when (state) {
        STATE_LINKED -> "linked"
        STATE_ON -> "on"
        else -> "off"
    }

    private fun openSystemSettings(action: String) {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { activity.startActivity(intent) }.isSuccess
        if (!ok) toast("本机没有对应的系统设置页")
    }

    private fun toast(text: String) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "StatusTray"

        /** 与 LauncherActivity.onRequestPermissionsResult 对上 */
        const val REQ_PHONE = 0x51

        private const val TOGGLE_GUARD_MS = 700L
        private const val TOGGLE_WIFI = 1
        private const val TOGGLE_BT = 2

        private const val STATE_OFF = 0
        private const val STATE_ON = 1
        private const val STATE_LINKED = 2
    }
}
