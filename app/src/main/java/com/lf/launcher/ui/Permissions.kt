package com.lf.launcher.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.lf.launcher.weather.WeatherLocator

/**
 * 权限总表：哪些要弹窗、哪些只能跳系统页、现在缺哪几项。
 *
 * ## 为什么集中到这里
 *
 * 原来每个权限都是"用到才申请"——信号格点了才要电话、开了自动定位才要位置。
 * 这对老用户是舒服的，但对**刚装完的新用户**很难发现：得先点到那个功能，才知道
 * 桌面少了什么。现在的策略是首次启动一次性把该给的都要齐（见 [LauncherActivity]），
 * 之后才永久静默，缺的交给设置页里的「权限」分组补。
 *
 * ## 三类权限，三种处理方式
 *
 * | 类别 | 例子 | 怎么给 |
 * |---|---|---|
 * | 普通权限 | 网络、蓝牙、改音频设置 | 装包即给，代码里不用管 |
 * | 危险权限 | 位置、电话 | [RUNTIME]，弹系统框 |
 * | 特殊权限 | 悬浮窗（地图卡圆角遮罩） | 只能跳系统页，见 [requestOverlay] |
 */
object Permissions {

    /**
     * 需要弹窗申请的权限。
     *
     * 位置两个是**同一组**：系统只会弹一个「使用期间允许」，给粗定位也算给了，
     * 所以判断"有没有位置权限"必须用 [WeatherLocator.hasPermission]（任一即可），
     * 不能用 [missing] 里逐个比 —— 用户只给粗略定位时，缺失列表里会一直挂着 FINE。
     */
    val RUNTIME = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_PHONE_STATE,
    )

    fun granted(ctx: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * 还缺哪些运行时权限。
     *
     * 位置单独处理：只要给了粗定位就算齐，不再纠结 FINE。
     */
    fun missingRuntime(ctx: Context): List<String> {
        val missing = mutableListOf<String>()
        if (!WeatherLocator.hasPermission(ctx)) {
            missing += Manifest.permission.ACCESS_FINE_LOCATION
            missing += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (!granted(ctx, Manifest.permission.READ_PHONE_STATE)) {
            missing += Manifest.permission.READ_PHONE_STATE
        }
        return missing
    }

    fun allRuntimeGranted(ctx: Context): Boolean = missingRuntime(ctx).isEmpty()

    /** 悬浮窗：地图卡要贴高德悬浮窗的圆角，没有这个权限遮罩挂不上 */
    fun overlayGranted(ctx: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(ctx)

    /** 跳「显示在其他应用上层」开关页。车机 ROM 可能没有这一页，返回 false 表示跳不过去 */
    fun requestOverlay(activity: Activity): Boolean {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${activity.packageName}"),
        )
        return runCatching { activity.startActivity(intent) }.isSuccess
    }

    /** 彻底拒绝（"不再询问"）后唯一的补救入口：应用信息页里手动开 */
    fun openAppDetails(activity: Activity): Boolean {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${activity.packageName}"),
        )
        return runCatching { activity.startActivity(intent) }.isSuccess
    }

    /**
     * 系统是不是已经不给弹框了。
     *
     * 只在**请求过之后**调用才有意义：还没请求过时 rationale 也是 false，
     * 那不代表被拒绝，只代表还没问。
     */
    fun isBlocked(activity: Activity, permission: String): Boolean =
        !granted(activity, permission) &&
            !activity.shouldShowRequestPermissionRationale(permission)

    /** 给人看的名字，拼提示文案用 */
    fun label(permission: String): String = when (permission) {
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        -> "位置"
        Manifest.permission.READ_PHONE_STATE -> "电话"
        else -> permission.substringAfterLast('.')
    }

    /** 「位置、电话」这种串，用于申请前的说明 */
    fun describe(list: List<String>): String =
        list.map { label(it) }.distinct().joinToString("、")
}
