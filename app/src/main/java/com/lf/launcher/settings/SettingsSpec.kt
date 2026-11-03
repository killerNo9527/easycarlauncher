package com.lf.launcher.settings

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import com.lf.launcher.R
import com.lf.launcher.home.MapAppLauncher
import com.lf.launcher.music.MusicController
import com.lf.launcher.weather.WeatherApi
import com.lf.launcher.weather.WeatherLocator
import com.lf.launcher.weather.WeatherStore

/** 设置页的条目清单。分组顺序对照原版：桌面 → 外观 → 卡片 → 音乐 → 地图 → 关于。 */
object SettingsSpec {

    fun build(context: Context): List<SettingItem> {
        val items = mutableListOf<SettingItem>()

        // 这里原来有「桌面」分组：开机自动启动（没有 BOOT_COMPLETED 接收者）和
        // 返回键回首页（返回键本来就恒回首页）—— 两项都不接线，0.9.5 删掉。
        items += SettingItem.Section("外观")
        items += SettingItem.ChoiceItem(
            Keys.THEME, "首页布局",
            "地图与卡片的左右关系，比例固定 图 8 : 乐 2",
            options = listOf(
                Defaults.THEME_MAP_LEFT to "左图右乐",
                Defaults.THEME_MAP_RIGHT to "左乐右图",
            ),
            default = Defaults.THEME, ready = true,
        )
        items += SettingItem.ChoiceItem(
            Keys.DAY_NIGHT, "昼夜模式",
            "跟随系统时，车机切换日夜，桌面实时跟着换配色",
            options = listOf(
                com.lf.launcher.ui.Appearance.FOLLOW to "跟随系统",
                com.lf.launcher.ui.Appearance.DAY to "日间",
                com.lf.launcher.ui.Appearance.NIGHT to "夜间",
            ),
            default = Defaults.DAY_NIGHT, ready = true,
        )
        items += SettingItem.SwitchItem(
            Keys.SHOW_STATUS_BAR, "显示顶部状态栏",
            "桌面顶部的时钟与日期",
            default = true,
        )
        // 左上角快捷应用：开关打开后才出现「选择」入口（没开的时候调它没有意义）
        items += SettingItem.SwitchItem(
            Keys.STATUS_SHORTCUTS, "状态栏快捷应用",
            "顶部左上角放 2~5 个应用图标，点一下直接打开。关闭时那里只显示三个点",
            default = false,
        )
        if (SettingsStore.getBool(Keys.STATUS_SHORTCUTS, false)) {
            val count = StatusShortcuts.resolve(context).size
            items += SettingItem.ActionItem(
                "选择快捷应用",
                "最多 ${StatusShortcuts.MAX} 个：点应用添加、再点一次移除，↑ / ↓ 调顺序，" +
                    "顺序就是左上角从左到右",
                value = if (count == 0) "未选择" else "$count 个",
            ) {
                context.startActivity(Intent(context, ShortcutPickerActivity::class.java))
            }
        }
        items += SettingItem.SwitchItem(
            Keys.SHOW_NAV_BAR, "显示底部导航",
            "底部条带：播放控制 + 首页 / 应用 / 设置入口。关掉后这些控件一起隐藏" +
                "（设置仍可从应用列表进入），页面会补回它占的高度",
            // 读的时候默认是 true（LauncherActivity 用 getBool(..., true)）：
            // 这里必须一致，否则开关显示「关」但条带还在，看起来像开关坏了
            default = true,
        )

        // 系统栏：开关打开后才显示四边留白的调节入口（关着的时候调它没有意义）
        items += SettingItem.SwitchItem(
            Keys.USE_SYSTEM_BARS, "使用系统状态栏 / 导航栏",
            "关闭时桌面独占整屏（隐藏系统两条栏）；开启后系统栏照常显示，" +
                "桌面内容自动避让。车机系统栏带空调 / 音量等原生控件时建议开启",
            default = false,
        )
        if (SettingsStore.getBool(Keys.USE_SYSTEM_BARS, false)) {
            val pad = com.lf.launcher.ui.SystemUi.customPaddingDp()
            items += SettingItem.ActionItem(
                "全局四边留白",
                "系统栏占位之外再补的边距（dp）。车机系统栏高度常报不准，用这里对；只作用于桌面",
                value = "上${pad[0]} 下${pad[1]} 左${pad[2]} 右${pad[3]}",
            ) {
                PaddingDialog.show(context) {
                    (context as? SettingsActivity)?.refreshItems()
                }
            }
        }

        items += SettingItem.Section("卡片")
        items += SettingItem.SwitchItem(Keys.CARD_TIME, "时间日期卡", "顶部的时间与大号时钟", default = true)
        items += SettingItem.SwitchItem(Keys.CARD_MUSIC, "音乐卡", "封面、歌名、歌词、进度与控制", default = true)
        items += SettingItem.SwitchItem(Keys.CARD_MONITOR, "负载卡", "CPU、内存、温度", default = true)
        items += SettingItem.ActionItem(
            "卡片顺序",
            "右栏这几张卡谁在上谁在下；地图卡固定在左边，不参与排序",
            value = CardOrder.summary(),
        ) {
            CardOrderDialog.show(context) { (context as? SettingsActivity)?.refreshItems() }
        }

        items += SettingItem.Section("音乐")
        items += SettingItem.SwitchItem(Keys.MUSIC_COVER, "显示封面", "无封面时用渐变占位", default = true)
        items += SettingItem.SwitchItem(Keys.MUSIC_LYRIC, "显示歌词", "读取播放器发布的歌词", default = true)

        items += SettingItem.Section("音效")
        items += SettingItem.SwitchItem(
            Keys.EQ_ENABLED, "全局均衡器",
            "31 段 1/3 倍频程，挂在全局输出混音上，所有 App 的声音都会过它",
            default = false,
        )
        items += SettingItem.ActionItem(
            "调整均衡器",
            "拖推子改曲线，内置「车机矫正」预设（实测 20 点拟合到 31 段，误差 0.02 dB）",
            value = eqPresetLabel(),
        ) {
            context.startActivity(Intent(context, com.lf.launcher.eq.EqActivity::class.java))
        }

        items += SettingItem.Section("地图")
        items += SettingItem.ActionItem(
            "当前地图",
            "点按地图区会打开这个应用的全屏导航",
            value = MapAppLauncher.selectedLabel(context),
        ) {
            context.startActivity(Intent(context, MapAppPickerActivity::class.java))
        }
        // 高德的悬浮窗只对"已经活着的进程"生效：冷启动前发 showmap 唤不起来，
        // 所以需要在桌面回到前台时先把它拉起来一次、两秒后再切回来
        items += SettingItem.SwitchItem(
            Keys.AUTO_MAP_ON_OPEN, "打开桌面时自动拉起地图",
            "桌面回到前台后先启动地图应用，2 秒后自动切回桌面。" +
                "高德的悬浮窗只对已经运行的进程生效，不先拉一次它不会出来",
            default = true,
        )
        // 集成期的调试项（广播静态探测、坐标全传 0、两个探针页）已随 0.9.4 一并删除：
        // 广播路径已经稳定，留着只会让设置页看起来像半成品
        items += SettingItem.SwitchItem(
            Keys.MAP_BORDER, "不绘制地图区边框",
            "开启后地图卡不再描边，地图与卡片边界自然融合",
            default = true, inverted = true,
        )
        items += SettingItem.ChoiceItem(
            Keys.MAP_BORDER_COLOR, "边框颜色",
            "地图区的描边颜色",
            options = listOf(
                "#4C8DFF" to "主题蓝",
                "#FFFFFF" to "白色",
                "#FFB020" to "琥珀",
                "#22D3EE" to "青色",
                "#8A929E" to "灰色",
            ),
            default = Defaults.MAP_BORDER_COLOR,
        )
        items += SettingItem.SwitchItem(
            Keys.AMAP_DAY_NIGHT, "高德日夜跟随桌面",
            "桌面切到夜间时，用高德官方广播协议把它也切成黑夜配色（深底亮路网）。" +
                "需要高德车机版 1.4.2 以上；手机版或未适配的改版高德不会响应",
            default = Defaults.AMAP_DAY_NIGHT,
        )
        items += SettingItem.Section("天气")
        items += SettingItem.SwitchItem(
            Keys.CARD_WEATHER, "天气卡",
            "右栏显示城市、温度、天气与今日高低温。要先在上面配好数据来源才取得到数据",
            default = false,
        )

        val jwt = WeatherStore.auth() == WeatherStore.AUTH_JWT
        items += SettingItem.ChoiceItem(
            Keys.WEATHER_AUTH, "认证方式",
            if (jwt) "JWT：用 Ed25519 私钥在本机现签 token，和风新项目只能这样"
            else "API KEY：老项目还能用，最省事（新项目已经没有这个选项了）",
            options = listOf(
                WeatherStore.AUTH_KEY to "API KEY",
                WeatherStore.AUTH_JWT to "JWT",
            ),
            default = WeatherStore.auth(),
        )

        if (jwt) {
            items += SettingItem.ActionItem(
                "JWT 凭据",
                "Project ID / Key ID / 私钥。私钥是整段 PEM，用「从手机填」导入比在车机上打字快得多",
                value = if (WeatherStore.jwtReady()) "已配置" else "未配置",
            ) {
                WeatherJwtDialog.show(context) { (context as? SettingsActivity)?.refreshItems() }
            }
            items += SettingItem.ActionItem(
                "API Host",
                "JWT 也要它：JWT 只替代 URL 里的 key 参数，请求还是打到账号的专属域名。" +
                    "新版和风每个账号一个（形如 abc.re.qweatherapi.com），老账号可留空走 devapi",
                // 去掉 scheme 再显示：域名本来就长，带上 https:// 会把左边的标题挤窄
                value = WeatherStore.qwHost().ifBlank { "默认（devapi）" }
                    .removePrefix("https://").removePrefix("http://"),
            ) {
                editHost(context) { (context as? SettingsActivity)?.refreshItems() }
            }
        } else {
            items += SettingItem.ActionItem(
                "和风 Key",
                "32 位字符，在 console.qweather.com 的「项目管理」里创建，只保存在这台车机上。" +
                    "表单里同时能填 API Host（新账号必填）。嫌打字麻烦就用「用手机填」",
                value = maskKey(WeatherStore.qwKey()),
            ) {
                WeatherKeyDialog.show(context) { (context as? SettingsActivity)?.refreshItems() }
            }
        }
        val autoCity = WeatherLocator.enabled()
        items += SettingItem.SwitchItem(
            Keys.WEATHER_AUTO_CITY, "城市跟随定位",
            "取一次车机坐标交给天气服务换城市名，开到别的城市会自动跟着变。" +
                "只在打开这个开关时才会申请定位权限，坐标只发给自己的天气服务",
            default = false,
            onToggle = { on ->
                if (on) {
                    (context as? SettingsActivity)?.ensureLocationPermission { ok ->
                        if (!ok) {
                            Toast.makeText(context, "没给定位权限，城市仍按手填的来", Toast.LENGTH_LONG).show()
                        }
                        (context as? SettingsActivity)?.refreshItems()
                    }
                }
            },
        )
        items += SettingItem.ActionItem(
            "城市",
            if (autoCity) "跟随定位自动更新；点这里立刻重新定位一次"
            else "看哪个城市的天气（国内填「北京」「上海」这种即可）",
            value = when {
                !autoCity -> WeatherStore.city()
                WeatherLocator.cachedLocation() == null -> "自动 · 待定位"
                else -> "自动 · ${WeatherStore.city()}"
            },
        ) {
            val refresh: () -> Unit = { (context as? SettingsActivity)?.refreshItems() }
            if (autoCity) locateNow(context) { refresh() }
            else editCity(context) { refresh() }
        }

        items += SettingItem.Section("媒体")
        val music = MusicController.snapshot()
        items += SettingItem.InfoItem(
            "连接状态",
            when {
                music.connected -> "已连接"
                music.error.isNotBlank() -> music.error
                else -> "未连接（先打开 md3Music 播一首）"
            },
        )
        items += SettingItem.InfoItem(
            "歌词",
            when {
                music.hasLyric -> "${music.lyricLineCount} 行"
                music.connected -> "未获取（需在 md3Music 里开启「歌词转发」）"
                else -> "—"
            },
        )
        items += SettingItem.ActionItem(
            "打开 md3Music",
            "桌面音乐卡的数据与歌词都来自这个播放器",
        ) {
            val intent = context.packageManager.getLaunchIntentForPackage(MusicController.MD3_PKG)
            if (intent != null) runCatching { context.startActivity(intent) }
        }

        items += SettingItem.Section("关于")
        items += SettingItem.InfoItem("版本", versionName(context))
        items += SettingItem.InfoItem(
            "屏幕",
            "${context.resources.displayMetrics.widthPixels}×${context.resources.displayMetrics.heightPixels}" +
                " · density ${context.resources.displayMetrics.densityDpi}",
        )
        items += SettingItem.InfoItem(
            "可用内存",
            readMemory(context),
        )
        items += SettingItem.InfoItem("系统", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")

        return items
    }

    private fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "--"
    }.getOrDefault("--")

    /** Key 在列表里只露头尾：够确认「填的是不是那一把」，又不会被旁边的人看全 */
    private fun maskKey(key: String): String = when {
        key.isEmpty() -> "未填"
        key.length <= 8 -> key
        else -> "${key.take(4)}…${key.takeLast(4)}"
    }

    private fun editHost(context: Context, after: () -> Unit) {
        editSingle(
            context,
            "和风 API Host",
            "abc.re.qweatherapi.com（留空用 devapi.qweather.com）",
            WeatherStore.qwHost(),
        ) {
            WeatherStore.setQwHost(it)
            after()
        }
    }

    /** 单行输入框的通用弹窗。留空算合法（Host 就允许空），怎么处理交给调用方 */
    private fun editSingle(
        context: Context,
        title: String,
        hintText: String,
        value: String,
        onSave: (String) -> Unit,
    ) {
        val input = android.widget.EditText(context).apply {
            hint = hintText
            setText(value)
            setSingleLine()
            textSize = 16f
            setSelection(text.length)
        }
        val box = android.widget.LinearLayout(context).apply {
            setPadding(dp(context, 20), dp(context, 8), dp(context, 20), 0)
            addView(input)
        }
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("保存") { _, _ -> onSave(input.text.toString().trim()) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 城市输入框：就一个字串，不值得单独开一页 */
    private fun editCity(context: Context, after: () -> Unit) {
        val input = android.widget.EditText(context).apply {
            setText(WeatherStore.city())
            setSingleLine()
            textSize = 16f
            setSelection(text.length)
        }
        val box = android.widget.LinearLayout(context).apply {
            setPadding(dp(context, 20), dp(context, 8), dp(context, 20), 0)
            addView(input)
        }
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("天气城市")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) {
                    WeatherStore.setCity(value)
                    after()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 立刻重新定位一次，并把反查到的城市名写进设置。
     *
     * 反查（坐标 → 城市名）在服务端做 —— 那一步要天气服务商的 key，App 里没有。
     * 所以这里是「拿坐标 → 要一次天气 → 服务端顺带告诉我们这是哪个城市」，
     * 一个请求同时解决了城市名和最新数据。
     */
    private fun locateNow(context: Context, after: () -> Unit) {
        val act = context as? SettingsActivity
        if (act == null) {
            after()
            return
        }
        act.ensureLocationPermission { granted ->
            if (!granted) {
                Toast.makeText(context, "没有定位权限，城市仍按手填的来", Toast.LENGTH_LONG).show()
                after()
                return@ensureLocationPermission
            }
            Toast.makeText(context, "正在定位…", Toast.LENGTH_SHORT).show()
            WeatherLocator.request(context) { loc ->
                if (loc == null) {
                    Toast.makeText(
                        context,
                        "定位不到（车机定位开关可能关着），仍用「${WeatherStore.city()}」",
                        Toast.LENGTH_LONG,
                    ).show()
                    after()
                    return@request
                }
                if (!WeatherStore.ready()) {
                    Toast.makeText(context, "坐标已记下，先把天气的数据来源配好才取得到天气", Toast.LENGTH_SHORT).show()
                    after()
                    return@request
                }
                WeatherApi.fetchAt(loc.first, loc.second) { result ->
                    result.onSuccess { w ->
                        WeatherStore.setCity(w.city)
                        WeatherStore.saveCache(w)
                        Toast.makeText(context, "定位到 ${w.city}，${w.temp}° ${w.text}", Toast.LENGTH_SHORT).show()
                    }.onFailure { e ->
                        Toast.makeText(
                            context,
                            "坐标已记下，但天气没取到：${e.message}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    after()
                }
            }
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun eqPresetLabel(): String = when (SettingsStore.getString(Keys.EQ_PRESET, "custom")) {
        "flat" -> "平直"
        "car" -> "车机矫正"
        else -> "自定义"
    }

    private fun readMemory(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return "--"
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        if (info.totalMem <= 0) return "--"
        val totalGb = info.totalMem / 1024.0 / 1024.0 / 1024.0
        val availGb = info.availMem / 1024.0 / 1024.0 / 1024.0
        return String.format("%.1f / %.1f GB", availGb, totalGb)
    }
}
