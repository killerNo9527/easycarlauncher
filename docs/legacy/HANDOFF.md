> **历史文档，内容已过时，不要按它改代码。**
>
> 这是开发中期的移交文档，写于 m25 左右。之后发生了这些变化：
> - 地图从「四通道（freeform / overlay / embed / launch）」重构为「广播控制 + 全屏启动」，
>   内嵌虚拟屏那条已确认需要系统签名，代码已删除
> - 行程卡已删除（车辆总线数据拿不到），四卡变三卡，后来补了天气卡
> - 文件数从 20 个 / 3060 行涨到 47 个 / 9700 行
> - 设置从 6 组 27 项变成 8 组
>
> 保留它是因为里面仍有价值的部分：为什么重写而不是改原厂 APK、media3 选型、
> 悬浮窗授权的降级链、以及一堆"已证伪不要再试"的结论。
>
> **当前架构请看 [`../ARCHITECTURE.md`](../ARCHITECTURE.md)。**

---

# CarLauncher 移交文档 · 当前功能与文件清单

> **更新（0.9.15-m25）**：**状态栏右侧四个图标都成了真控件**——信号格（自绘 4 档，读
> `SignalStrength.getLevel()`）、Wi‑Fi、蓝牙、设置。Wi‑Fi / 蓝牙单击即真实开关、长按进系统设置页，
> 三档状态分别是「已连接=加粗 / 已开未连=带? / 已关=斜杠」；信号读不到时画斜杠。
> 新增 `ui/StatusTray.kt`、`ui/SignalBarsView.kt` 与 6 个三态矢量图；权限新增
> `ACCESS_WIFI_STATE / CHANGE_WIFI_STATE / BLUETOOTH / BLUETOOTH_ADMIN / READ_PHONE_STATE`。
> 排查用 `adb logcat -s StatusTray`（每次状态变化打一行，含 `tap wifi/bt` 与防抖丢弃）。
> 详见 `0.9.15-m25-变更说明.md`。
>
> **更新（0.9.14-m24）**：应用列表**长按图标弹菜单**（应用信息 / 卸载），对齐系统原生：
> 系统预装应用只给「应用信息」（按 `FLAG_SYSTEM` 且非 `FLAG_UPDATED_SYSTEM_APP` 判定），
> 卸载走 `ACTION_DELETE` 让系统弹确认框。**必须声明 `REQUEST_DELETE_PACKAGES`**，
> 否则 UninstallerActivity 直接拒绝、界面毫无反应（`startActivity` 不报错，容易误判）。
> 菜单是自绘 PopupWindow（190dp / 17sp 深色），带长按震动反馈。
>
> **更新（0.9.13-m23）**：**应用列表改成实时刷新**。原来只在 `onViewCreated` 里扫一次包名，
> 桌面是 HOME 长期不重建 → 新装应用不重启就看不到。现在 `onStart` 注册包广播
> （ADDED / REMOVED / REPLACED / CHANGED + `package` data scheme，300ms 防抖），
> `onStop` 注销；回前台再补扫一次，覆盖"在后台装完切回来"这条路。
> 排查用 `adb logcat -s AppList`（每次重扫打印条目数）。
>
> **更新（0.9.12-m22）**：均衡器页支持**双击推子输入数值**（31 段挤在一起时靠拖对不准，
> 双击弹深色输入框，带归零）；弹窗固定深色主题（`EqDialogTheme` + `bg_eq_dialog`）。
> 同时把 31 段引擎抽成**独立 App FreeBand**（独立工程 eqapp（现为单独的 FreeBand 仓库），包名 `com.lf.freeband`，
> 零第三方依赖），带前台服务保活 —— 因为 `AudioEffect` 绑创建它的进程，独立版没有 HOME 待遇。
> 交付 `FreeBand-1.1.apk`。**桌面版与独立版同时开会抢 session 0，只开一个。**
>
> **更新（0.9.11-m21）**：**桌面有了真的 31 段全局均衡器**（`DynamicsProcessing` 挂 session 0）。
> 关键在于之前的「-5 失败」是我把 `VARIANT_FAVOR_FREQUENCY_RESOLUTION(=0)` /
> `VARIANT_FAVOR_TIME_RESOLUTION(=1)` 记反了 —— 用 0 就能建 31 段（重启后复验仍成立）。
> 新增 `peq/`（上游算法与 SystemEq）、`eq/`（EqEngine / EqEditorView / EqActivity / EqSettings）、
> 布局 `activity_eq`、设置页「音效」分组、`MODIFY_AUDIO_SETTINGS` 权限。
> 你的 20 点实测矫正曲线已拟合为 31 段内置预设（误差 0.022 dB）。
>
> **更新（0.9.10-m20）**：**删掉行程卡与状态栏瞬时功耗**。这两块都依赖车辆总线，而这台车机上
> 只走主机厂私有 binder（参照版本那条路），拿不到 → 常显 `--`。随之删掉整个 `vehicle/` 包、
> 设置里的「车辆」分组（含打印诊断 / 行程清零 / vendor 属性实验）、`ic_bolt` 图标与相关配置键。
> 右栏从四张卡变三张，权重 `56 : 19 : 17` → `56 : 17`，**音乐卡高度占比 61% → 77%**，歌词区更宽裕。
> 负载卡（CPU / 内存 / 温度）保留，本机可读。详见 `0.9.10-m20-变更说明.md`。
>
> **更新（0.9.9-m19）**：**长歌词扫到句尾就停，不再回开头**。砍掉上一版的关键帧回程段
> （`20% 看开头 / 30% 扫过去 / 50% 停在句尾`）。同时修掉更隐蔽的一层：`restart()` 有四个入口
> （`setText` 后的 post、`onSizeChanged`、`onVisibilityChanged`、`onAttachedToWindow`），换句时
> 一起触发会把 offset 反复清零 —— 日志实测同一句 2ms 内被启动两次。现在用 `文本|宽度|字号`
> 做 key，没变就不动进度。暂停也从「归零重扫」改成 `ValueAnimator.pause()/resume()`。
> 详见 `0.9.9-m19-变更说明.md`。
>
> **更新（0.9.8-m18）**：**长歌词终于完整了**。上一版溢出时靠 `super.onDraw` + `translate`，
> 画什么/画在哪取决于 TextView 内部 Layout（`VERY_WIDE`、ellipsize、gravity、自裁剪），
> 模拟器正常、**车机上只在中间开一扇窗**（就是那三张「句子中间被切、右边留白」的照片）。
> 现在溢出时**自己 `canvas.drawText`**：测量与绘制同一个 Paint、同一个字符串，不再依赖排版。
> 附带修：极小溢出当放得下、行尾空格 `trimEnd`、字号先于文本设置。节奏改为**在停留预算内往返**
> （20% 开头 / 33% 扫过去 / 20% 句尾 / 27% 扫回来），保证长句两头都看得到。详见 `0.9.8-m18-变更说明.md`。
>
> **更新（0.9.7-m17）**：**歌词滚动修好了**——根因是时间不是宽度：来回滚一趟要 8.8s，而一句歌词
> 只停留 2~3s，**没扫到句尾就换句**，所以只看得到开头一小段。改成单向（看开头 → 扫到句尾 → 停在句尾），
> 且扫描时长由 `setSweepBudget()` 按「这句还能显示多久」下发，保证换句前扫完。
> 同时歌词行数由写死 5 行改成**按高度分 3 / 5 / 7 档**，行在 `HomeFragment.buildLyricRows()` 里生成。
> 详见 `0.9.7-m17-变更说明.md`。
>
> **更新（0.9.6-m16）**：**音乐卡字号整体放大**（三档各 +15~20%，底栏歌名/歌手从写死的 15/11sp
> 抽成 dimen 并放大）；**当前歌词「折行」改「横向来回滚」**——新增 `ui/ScrollTextView`
> （放得下就居中不动，超宽才滚；自己实现而不是 `ellipsize=marquee`，因为它要 selected 才跑、
> 且是单向循环），翻译行因此从当前行的 Spannable 里拆成独立控件（`lyric_translation`，
> 无翻译时 GONE）。播放暂停时滚动同步停住。详见 `0.9.6-m16-变更说明.md`。
>
> **更新（0.9.5-m15）**：**清理设置里没有实际功能的选项**。判据是「改了什么都不会发生」：
> 删掉开机自动启动（无 BOOT_COMPLETED 接收者）、返回键回首页（行为恒定）、封面旋转（无实现）、
> 歌词行数（说明项且与实现不符）、地图自启动（无读取方）、播放器（纯常量），
> 并清掉对应配置键；「桌面」分组空了，整组删掉。顺带修正「显示底部导航」声明 false、
> 读取 true 的不一致（会让开关看着像坏了）。详见 `0.9.5-m15-变更说明.md`。
>
> **更新（0.9.4-m14）**：① **关掉桌面状态栏后空间回收**（顶部间距 52dp → 24dp，底栏同理归 0）；
> ② **首页布局只剩两种**：「左图右乐」（默认）/「左乐右图」，比例仍 图8:乐2，
> 用 `ConstraintSet` 从布局文件重新 clone 再改，双向切换不残留约束，地图悬浮窗跟着换边；
> ③ **清掉地图调试项**：坐标全传 0 开关、广播探测/说明、两个探针入口连同整个 `probe/` 包与清单条目。
> 详见 `0.9.4-m14-变更说明.md`。
>
> **更新（0.9.3-m13）**：**系统栏开关 + 全局四边留白**。设置 → 外观新增「使用系统状态栏 / 导航栏」，
> 打开后系统两条栏照常显示，桌面内容按 `insets + 自定义留白` 让位；留白入口（四根 SeekBar，
> 0~96dp，上/下/左/右）只在开关打开后出现。布局为此加了 `content_root` 内容容器：
> 壁纸与 scrim 留在外面满屏，只有内容层吃 padding。`ui/SystemUi` 统一两种模式的
> systemUiVisibility 与 inset 计算，桌面与设置页共用。详见 `0.9.3-m13-变更说明.md`。
>
> **更新（0.9.2-m12）**：**歌词「卡在暂无歌词」修好了**。定位结论：md3Music 换歌时推来的 metadata
> **不带 lyricInfo**，等歌词加载完**不会补推**；而同一时刻重连会话就能读到完整歌词 →
> 这不是我们取太慢，是对方不补推。处理：歌词 0 行且正在播放时按 600ms/1.5s/3s/6s 重连补拉
> （最多 4 次，拿到即停），补拉期间歌词区留白不写「暂无歌词」。
> 顺带修掉日志刷屏（`MapMaskOverlay` 无权限时每秒几十次 addView 重试、地图卡文案在布局中反复
> setText），它一度把排查日志挤出 logcat。详见 `0.9.2-m12-变更说明.md`。
>
> **更新（0.9.1-m11）**：**矮屏适配**。换屏幕比例（1920×1080 → 1920×740）后音乐卡被压成一条缝：
> 右栏可用高度只剩 335dp，而四张卡需要 ≈470dp，且 5 行歌词 + 封面本身就有 200dp 下限。
> 改为**按高度分档**（`values/` 默认=紧凑、`values-h600dp/`=标准、`values-w1400dp/`=大屏，
> 因为限定符只有最小高度）+ 结构降级（歌词 5 行→3 行，`@integer/lyric_lines`）+ 框架尺寸抽 dimen
> （底栏/页面让位/列底边距）。实测 740 / 1080 / 2560×1080 三档都正常。详见 `0.9.1-m11-变更说明.md`。
>
> **更新（0.9.0-m10）**：**右栏顶边对齐**（第一张可见卡不再带 topMargin，`marginTop` 统一成
> `status_bar_height`）；**删掉状态栏的「高德悬浮窗 · 已配置」胶囊**（调试态 + 它自带 padding
> 把状态栏顶得不齐）；**状态栏新增瞬时功耗 `⚡ 12.5 kW`**，并新增 `vehicle/` 数据层
> （`VehicleData` / `CarPropertySource` / `VehicleProbe`），行程卡三格第一次有真数据。
> **数据源核对结论：我们原来没有数据源，与参照版本不一致** —— 参照版本走车内 CarService 的
> vendor 属性（日志实证 `CarServiceBinderPropertyClient` + propId `0x21405019/0x21405021/
> 0x21405178/0x2160e003`），私有 binder 拿不到 AIDL，只能先把可插拔数据层 + 诊断做好，
> 实车跑一次「打印车辆诊断」即可定映射。详见 `0.9.0-m10-变更说明.md`。
>
> **更新（0.8.0-m9）**：音乐卡视觉收细 —— 封面缩到 **48dp 圆形**（新增 `ui/CircleArtView.kt`，
> 用 BitmapShader 画圆，绕开本机 `clipToOutline` 不生效），底栏封面同款；歌词**水平居中**、
> 当前行 19sp / 其余 15sp、**换行淡入上浮动画**；当前行下方按 `（翻译）` 显示 `lyricInfo` 的
> translation 轨（实测可用，46/50、70/71 行）。详见 `0.8.0-m9-变更说明.md`。
>
> **更新（0.7.1-m8）**：音乐卡顶部**歌名/歌手改为与封面垂直居中**（packed 链）；
> **切歌后界面不同步**已修 —— 根因是 md3Music 换歌只更新会话、不推元数据事件，
> Media3 控制器的 title/duration/position 全冻结；改为「事件到达但不带元数据 / 手动切歌 / 位置异常」
> 三种信号触发重连刷新，并修了歌词时间轴缓存导致换歌后歌词不换。详见 `0.7.1-m8-变更说明.md`。
>
> **更新（0.7.0-m7）**：**跟随系统日夜模式**。颜色拆成 `values/colors.xml`（日间）与
> `values-night/colors.xml`（夜间）两套同名资源，主题 `Theme.AppCompat.DayNight`；
> 设置页「昼夜模式」支持 跟随系统 / 日间 / 夜间。坑：清单里的 `uiMode` configChanges 让系统不重建，
> 且 `setDefaultNightMode` 会被 AppCompat 覆盖 —— 详见 `0.7.0-m7-变更说明.md`。
>
> **更新（0.6.0-m6）**：地图卡**有圆角了**。高德不吃任何圆角参数（8 个候选 extras 全试过），
> 改为在它上面盖一层不可触摸的遮罩窗口（`map/MapMaskOverlay.kt`）：四角填桌面背景、
> 一圈填卡片自身 drawable。触摸穿透、切页收放均已实测。详见 `0.6.0-m6-变更说明.md`。
>
> **更新（0.5.0-m5）**：底栏中间的**红心已删除**，改为上一首（md3Music 无收藏写接口，
> 详见 `md3Music-收藏接口调研.md`）；试过的 **MCP 数据通道整个撤回**，音乐只走 MediaSession。
> 修了底栏封面一直空白（`lastArt` 缓存被卡片和底栏共用）。详见 `0.5.0-m5-变更说明.md`。
>
> **更新（0.4.0-m4）**：地图**只剩广播一条路**（showmap / closemap），其余四种方案代码已删；
> 音乐卡改 5 行歌词、卡内控件全部搬到新增的**全局底栏**。
> **实测结论有两条推翻早期判断**：官方高德 9.1.0 其实认这套广播（接收者运行时注册，静态探测查不到）；
> `w`/`h` 是右下角坐标不是宽高。详见 `0.4.0-m4-变更说明.md`。
>
> 本文件其余章节（HOME 接管、音乐卡、设置系统、尺寸体系、id 契约）依然有效。
>
> 用途：让全新会话在不看历史对话的情况下，完整还原本工程现有功能，并在此基础上重做首页卡片。
> 编写时间：2026-10-31 · 对应构建 `0.1.0-m0`（versionCode 1）、产物 `app/build/outputs/apk/debug/app-debug.apk`
> 工程根目录：`F:\AIWork\WbuddyWOKR\CarLauncher`（注意目录名是 **WOKR**，不是 WORK）

---

## 0. 一句话现状

一个从零自研的 Android 车机桌面：**HOME 接管 + 全屏沉浸 + ViewPager 双页（首页 / 应用）+ 四张可显隐的数据卡 + 真实音乐与滚动歌词 + 地图区四通道 + 27 项设置**。
界面骨架、控件 id、dimen 命名刻意沿用原版**菱粉桌面**，代码全部自研。业务逻辑已跑通，**视觉形态仍是 M0 骨架级**——这正是下一步要重做卡片页的原因。

---

## 1. 运行环境与构建

| 项 | 值 |
|---|---|
| 目标设备 | MT8666 / 6GB / 1920×1080 / Android 9（API 28） |
| applicationId | `com.lf.launcher` |
| minSdk / targetSdk / compileSdk | 28 / **28** / 34 |
| 语言与 UI | Kotlin 2.0.0 / 原生 View（ViewBinding），**无 Compose、无 Flutter** |
| 构建 | AGP 8.13.2 + Gradle 9.1.0 + JDK 21 |

**targetSdk 刻意停在 28**：避开 Android 10+ 的后台启动限制与分区存储，车机上少一半坑。改这个值会连带影响 `MediaProjection`、`startActivity` 后台启动、文件路径，**不要随手升**。

```bash
# 构建（本机没有 gradlew，用系统 gradle）
export JAVA_HOME=F:/AITools/jdk21
GRADLE=~/.gradle/wrapper/dists/gradle-9.1.0-all/*/gradle-9.1.0/bin/gradle
cd /path/to/CarLauncher
$GRADLE :app:assembleDebug
# 产物 app/build/outputs/apk/debug/app-debug.apk（约 19MB，debug 签名，可直接装）
```

`local.properties` 已指向 `F:\AITools\android-sdk`；`settings.gradle.kts` 依赖源优先阿里云镜像。

**依赖仅 6 个**（`app/build.gradle.kts`）：core-ktx、appcompat、constraintlayout、recyclerview、viewpager2、cardview，外加 **media3-session**（读歌词必需，见 4.3）。

---

## 2. 文件清单

### 2.1 Kotlin 源码（20 个文件 / 3060 行）

| 路径（`app/src/main/java/com/lf/launcher/`） | 行数 | 作用 | 状态 |
|---|---|---|---|
| `LauncherActivity.kt` | 165 | HOME 入口：全屏沉浸、壁纸、ViewPager 双页、顶部状态栏、底部导航 | 可用 |
| `home/HomeFragment.kt` | 579 | 首页总装：inflate 主题、时钟、负载、音乐/歌词、地图区、卡片显隐 | 核心 |
| `home/MusicController.kt` | 254 | Media3 MediaController 接 md3Music：曲目、封面、进度、歌词 extras | 可用 |
| `lyric/LyricInfo.kt` | 360 | 歌词 JSON 解析（LRC / ELRC 逐字）+ 时间轴 + 播放时钟插值 | 可用 |
| `home/MapEmbedView.kt` | 203 | 内嵌地图：SurfaceView → VirtualDisplay → 把地图 Activity 拉上去 + 触摸转发 | **受权限限制** |
| `home/MapWidgetClient.kt` | 194 | 高德 `AutoSimilarWidgetService` 的 Binder 客户端（手写 Parcel） | **验证未通过** |
| `home/MapEmbedCapability.kt` | 82 | 5 项系统级权限探测 + 实际建屏试一次 | 可用（诊断） |
| `home/FloatingMapLauncher.kt` | 233 | 自由窗口 / 全屏 / 自启动三通道拉起地图 | 可用 |
| `home/MapHeaderOverlay.kt` | 123 | `TYPE_APPLICATION_OVERLAY` 盖住自由窗口自带的系统标题栏 | 可用（需授权） |
| `home/OverlayPermission.kt` | 134 | 悬浮窗授权四级降级（已授权 / root appops / 系统页 / adb 命令） | 可用 |
| `apps/AppRepository.kt` | 48 | PackageManager 查可启动应用（排除自己、去重、按名排序） | 可用 |
| `apps/AppListFragment.kt` | 110 | 应用网格，列数按屏宽自适应；**第一格是「桌面设置」伪条目** | 可用 |
| `settings/SettingsStore.kt` | 95 | SharedPreferences 读写 + `Keys` 常量 + `Defaults` 默认值 | 可用 |
| `settings/SettingItem.kt` | 36 | 设置条目模型：Section / Switch / Choice / Info / Action | 稳定契约 |
| `settings/SettingsSpec.kt` | 231 | 设置项清单（6 组 27 项），唯一需要改的地方 | 可用 |
| `settings/SettingsAdapter.kt` | 156 | 多类型 Adapter，支持 `inverted` 反向开关 | 可用 |
| `settings/SettingsActivity.kt` | 56 | 设置页宿主，`onResume` 重建条目刷新右侧值 | 可用 |
| `settings/MapAppPickerActivity.kt` | 85 | 地图应用选择器（网格） | 可用 |
| `probe/MapProbeActivity.kt` | 81 | **临时验证页**：把高德渲染进自己 Surface，验证 AIDL 通道 | 调试用，可删 |

### 2.2 布局（16 个）

| 路径（`app/src/main/res/layout/`） | 作用 |
|---|---|
| `activity_launcher.xml` | 桌面根：`root_view` → `wallpaper_bg` → `view_pager` → `statusBar` → `navigationBar` |
| `fragment_home.xml` | 首页根：`fg_home`、`map_host`（地图区）、`home_theme_host`（主题宿主，**换肤换这里**） |
| `home_theme_classic.xml` | 经典主题：右栏 `right_container_widgets` 内四张卡，高度用 **weight 52 / 19 / 18** |
| `include_time_date_card.xml` | 时间卡：`time_text` / `time_ampm` / `date_text` / `lunar_text` |
| `include_music_card.xml` | 音乐卡（205 行，最复杂）：封面、歌名歌手、4 行歌词、进度、三键 |
| `include_travel_card.xml` | 行程卡：`travel_distance` / `travel_duration` / `travel_consumption`（**静态占位**） |
| `include_monitor_card.xml` | 负载卡：`monitor_cpu` / `monitor_mem` / `monitor_temp`（真实数据） |
| `fragment_app_list.xml` / `item_app.xml` | 应用网格与单元格 |
| `activity_settings.xml` / `item_setting_*.xml`（4 个） | 设置页与四类条目 |
| `activity_map_app_picker.xml` | 地图选择器 |

**drawable 11 个**：`bg_card`、`bg_album_cover`、`bg_control_play`、`bg_control_button`、`bg_map_placeholder`、`bg_app_focus`、`bg_setting_row`、`bg_wallpaper_default`、`progress_music`、`ic_launcher`、`ic_settings`。

### 2.3 资源值与主题

| 文件 | 内容 |
|---|---|
| `values/colors.xml` | 深色主题 12 色（见 6.1） |
| `values/dimens.xml` | 默认档（宽 < 1400dp）：density 1.75 时对应 1097×617dp |
| `values-w1400dp/dimens.xml` | 大屏档（≥ 1400dp）：density 1.0 时对应 1920×1080dp |
| `values/themes.xml` | `Theme.CarLauncher`（无 ActionBar 全屏） |
| `values/styles_lyric.xml` | 歌词行样式 |
| `values/strings.xml` | `app_name` 等 |

> **分档只放字号和栅格**。卡片宽高全部走百分比 / weight，见 6.2——这是之前踩过溢出坑后的修正。

### 2.4 工程根目录

| 文件 | 说明 |
|---|---|
| `README.md` | M0 骨架对照表、尺寸推导、M0 实测结果（**部分内容已过时**，以本文档为准） |
| `app/build.gradle.kts` | 版本、targetSdk 28、6 个依赖 |
| `settings.gradle.kts` | 阿里云镜像仓库、`rootProject.name` |
| `local.properties` | `sdk.dir=F:\AITools\android-sdk` |
| `frida-kit/`、`hook_amap.js`、`hook_mcar.js`、`run_hook.py` | **逆向氢桌面的实验脚本，与 Launcher 运行无关**，可整体移除 |
| `*.png`（n3~n9、o1~o4、p2 等）、`preview/` | 早期对照截图，无用 |

---

## 3. 运行时结构

> ⚠️ 下图是 M0 骨架期的结构，**地图分支已重写**（见顶部那份文档表）。音乐、页面、设置三条仍准确。

```
LauncherActivity (HOME / singleTask / 横屏锁定 / 全屏沉浸)
├── wallpaper_bg        读系统壁纸，失败降级 bg_wallpaper_default
├── view_pager          ViewPager2，2 页，offscreenPageLimit=1，允许滑动
│   ├── [0] HomeFragment
│   │     └── home_theme_host → inflate home_theme_classic
│   │           ├── map_host      地图区（点击→拉起地图；长按→设置）
│   │           └── right_container_widgets
│   │                 ├── time_date_card   (weight 固定高 68/96dp)
│   │                 ├── music_card       (weight 52)
│   │                 ├── travel_card      (weight 19)
│   │                 └── monitor_card     (weight 18)
│   └── [1] AppListFragment             网格，第一格=「桌面设置」
├── statusBar           时间/日期/"M0 · 骨架"（15s 刷新，可关）
└── navigationBar       首页/应用（**默认隐藏**，可关）
```

**双页都常驻**（`offscreenPageLimit=1`），所以 `HomeFragment` 的 150ms 音乐刷新在切到应用页后依然在跑——重做卡片页时要留意这一点。

### 3.1 调用依赖（谁依赖谁）

```
HomeFragment ──> MusicController ──> LyricInfo(LyricTimeline/PlaybackClock)
     │                └─> Media3 MediaController ──> md3Music MediaSessionService
     ├──> FloatingMapLauncher ──> SettingsStore（读 map_app / map_mode / map_border）
     ├──> MapEmbedView ──> MapProjection(全局 MediaProjection 令牌)
     │        └─> MapEmbedCapability（有没有系统级权限）
     ├──> MapHeaderOverlay ──> OverlayPermission
     └──> SettingsStore（所有卡片显隐、歌词行数）

LauncherActivity ──> SettingsStore（状态栏/导航栏显隐）、MapEmbedCapability（启动打日志）
SettingsActivity ──> SettingsSpec ──> SettingItem[]
                         └─> FloatingMapLauncher / MapEmbedCapability / OverlayPermission（读状态显示）
```

---

## 4. 核心模块详解

### 4.1 LauncherActivity —— 桌面宿主

- **HOME 接管**：`MAIN + HOME + DEFAULT + LAUNCHER`，`singleTask`，`screenOrientation=landscape`，`resizeableActivity=false`
- **全屏沉浸**：`systemUiVisibility` 六个 flag 全开（`IMMERSIVE_STICKY`）。`onResume` 和 `onWindowFocusChanged(true)` 都要重新申请——从设置页返回会丢
- **设置即时生效**：`onResume → applySettings()` 重读 `SHOW_STATUS_BAR` / `SHOW_NAV_BAR`
- **返回键不退出**：`onBackPressed` 只把 pager 拨回第 0 页
- **`returnToHome` 静态标志**：设置页 `finish()` 时置位，桌面回前台切回首页。不加这个，改完设置看到的是应用页，像没生效
- 状态栏时间每 15s 刷一次；文案写死 `"M0 · 骨架"`

### 4.2 HomeFragment —— 卡片总装

- 布局用「主题宿主」间接 inflate：`R.id.home_theme_host` 里塞 `home_theme_classic`。**换主题/重做卡片就换这一层**
- 三个刷新循环，都用 `Handler.postDelayed`：
  - `tick` 5000ms：时钟 + 负载
  - `musicTick` 150ms：音乐 + 歌词（位置插值靠 `PlaybackClock`，不是靠回调频率）
- **负载卡真实数据**：CPU 用 `/proc/stat` 两次差分；内存用 `ActivityManager.MemoryInfo`；温度扫 `thermal_zone*/temp` 取最大
- **地图区** `map_host`：点击按当前模式分派；长按进设置；背景是可配颜色的描边 `GradientDrawable`（内嵌模式 1px、其余 2px，圆角 `home_card_corner_radius`）
- **卡片显隐** `applySettings()`：读 4 个 `CARD_*` + `MUSIC_COVER` + `MUSIC_LYRIC` + `LYRIC_LINES`（2 行模式隐藏首尾两行）

### 4.3 音乐卡（M1 完成的唯一"真功能"）

- 接的是 **md3Music**：`com.md3music.md3music` / `.MD3MusicMediaSessionService`
- **必须用 Media3 的 `MediaController`，不能用 framework 的**：歌词在 `MediaItem.mediaMetadata.extras["lyricInfo"]`，而 `android.media.MediaMetadata` 没有 `extras` 字段，走 framework 永远读不到歌词
- 前置条件：md3Music 设置里要打开「**歌词转发**」（`settings_lyric_info_enabled`）
- 歌词解析支持 LRC 与 ELRC（逐字），`LyricInfoParser.parse()` → `LyricTimeline.lineAt(pos)`
- 4 行展示，**当前行固定在第 2 行**（上一句 / 当前 / 下一句 / 再下一句），切行时换字号与颜色
- 无歌词时三种占位文案：`未连接播放器` / `暂无歌词` / `请在播放器里开启「歌词转发」`
- 控制：播放暂停 / 上一曲 / 下一曲；点封面或歌名打开播放器

**重做卡片页时这段可以直接复用**，接口只有 `MusicController.snapshot()` 和 `positionMs()`。

### 4.4 地图区 —— 四条通道，只有两条能用

这是本项目试错最多的部分，结论要照抄，别再走一遍：

| 通道 | 实现 | 结论 |
|---|---|---|
| **A. 内嵌（虚拟屏）** | `MapEmbedView`：SurfaceView → `VirtualDisplay` → `ActivityOptions.setLaunchDisplayId` 把地图 Activity 拉上去 → 触摸转发 | **需要系统签名权限**（`CAPTURE_VIDEO_OUTPUT` / `ADD_TRUSTED_DISPLAY` / `MANAGE_ACTIVITY_STACKS` / `INJECT_EVENTS`）。普通安装拿不到，`setLaunchDisplayId` 必报 Permission Denial。代码留着，靠 `MapEmbedCapability.canHostActivities()` 门控，不满足直接不建屏 |
| **B. 自由窗口** | `FloatingMapLauncher.startFreeform`：`ActivityOptions.setLaunchWindowingMode(5)` + `setLaunchBounds`，失败退 `su am start --windowingMode 5` | **可用（默认）**。就是原版菱粉桌面同款：地图以系统浮窗压在卡片位置，实时刷新、触摸直落。代价是窗口顶部带一条系统标题栏 |
| **C. 盖标题栏** | `MapHeaderOverlay`：`TYPE_APPLICATION_OVERLAY` 在卡片 rect 顶部盖一条 43dp 的自绘栏（全屏 / 关闭） | **可用，需 SYSTEM_ALERT_WINDOW**。授权走 `OverlayPermission` |
| **D. 全屏导航** | 直接 `startActivity` | 可用，作为兜底 |

**已证伪、不要再试的两条**（都是逆向氢桌面时踩的）：

1. **高德 `UsbFillActivity` 不建浮窗**。`com.autonavi.amapauto/com.autonavi.auto.remote.fill.UsbFillActivity` 只是个启动器 + 崩溃自修复页，最后 `startActivity(MainMapActivity)`，权限框判的是存储权限，跟悬浮窗无关。
2. **高德真正的浮窗是 `com.autonavi.autofloat.FloatWindowService`**（`type=2038, fmt=RGBA_8888, flags=NOT_FOCUSABLE, gravity=TOP|LEFT`，参 `bundle_layout_type`）。**清单未导出**，普通应用起不来；root 下强开出来的是**车速/限速小窗**，不是地图卡。
3. **`AutoSimilarWidgetService`（`MapWidgetClient`）**：AIDL `addSurface` 返回 true，但画面永远不出来——那块 Surface 得由高德自己驱动。`MapProbeActivity` 就是为验证这条写的。

> 结论：**普通第三方 Launcher 在高德车机版上没有"把地图浮进卡片"的可用入口。** 要真嵌入只有系统签名一条路。

### 4.5 应用列表

- `AppRepository.loadLaunchable()` 查 `MAIN + LAUNCHER`，排除自己、按包名去重、按 label 排序
- 列数 `(screenWidthDp / 140).coerceIn(3, 8)`
- **第一个格子是伪条目「桌面设置」**（伪包名 `__settings__`，蓝色齿轮图标）。因为底部导航默认隐藏，这里是进设置的**唯一常规入口**
- 点按直接 `startActivity(entry.launchIntent)`

### 4.6 设置系统

四层，改动只在最后一层：

```
SettingItem（模型：Section/Switch/Choice/Info/Action，Switch 支持 inverted 反向）
    ↑ SettingsSpec.build(context)   ← 27 项清单，改这里
    ↑ SettingsAdapter              ← 多类型渲染
    ↑ SettingsActivity             ← 宿主，onResume 重建以刷新右侧值
    ↑ SettingsStore                ← SharedPreferences("launcher_settings") + Keys/Defaults
```

**6 组 27 项**：桌面(2) → 外观(4) → 卡片(4) → 音乐(4) → 地图(10，含授权/探测) → 关于。

`SettingItem` 有 `ready: Boolean` 字段，未接入实现的项在 UI 上显示「待接入」（目前 `AUTO_START` 开机自启、`DAY_NIGHT` 昼夜模式、`THEME` 多主题是 `ready=false`）。

### 4.7 悬浮窗授权（`OverlayPermission`）

四级降级，因为车机基本砍了「其他应用」设置页：

1. 已授权 → 直接返回
2. 有 su 文件 → `appops set <pkg> SYSTEM_ALERT_WINDOW allow`
3. 有系统页 → `ACTION_MANAGE_OVERLAY_PERMISSION`
4. 都没有 → 弹窗给 adb 命令

坑：
- `SYSTEM_ALERT_WINDOW` 是 **appop，不是运行时权限**，`pm grant` 无效
- 探测 root 只 `File.exists()` 看 su 路径，**绝不执行 su**——执行会弹 SuperSU 授权框，设置页刷新一次弹一次
- 清单必须有 `ACCESS_SUPERUSER`，否则 SuperSU 报「未在 manifest 中定义」

### 4.8 探针页（可删）

`probe/MapProbeActivity`，`exported=true`，验证高德 AIDL 小部件通道用，已证伪。重做卡片页时可整体移除（连同 `home/MapWidgetClient.kt`）。

---

## 5. 配置项全表

存储：`SharedPreferences("launcher_settings")`，全在 `SettingsStore.kt`。

| Key | 默认 | 作用 | ready |
|---|---|---|---|
| `auto_start` | true | 开机自启（未实现） | ✗ |
| `back_to_home` | true | 返回键回首页（未接，目前恒定行为） | ✗ |
| `theme` | classic | 首页布局（目前只有 classic 一套实现） | ✗ |
| `day_night` | auto | 昼夜模式（未实现） | ✗ |
| `show_status_bar` | true | 顶部状态栏 | ✓ |
| `show_nav_bar` | **false** | 底部导航 | ✓ |
| `card_time` | true | 时间卡 | ✓ |
| `card_music` | true | 音乐卡 | ✓ |
| `card_travel` | true | 行程卡 | ✓ |
| `card_monitor` | true | 负载卡 | ✓ |
| `music_cover` | true | 显示封面 | ✓ |
| `music_vinyl` | false | 封面旋转（未实现） | ✗ |
| `music_lyric` | true | 显示歌词 | ✓ |
| `lyric_lines` | "4" | 歌词行数 4/2 | ✓ |
| `map_mode` | **freeform** | 地图区方式：`freeform` / `amap_plus` / `overlay` / `embed` / `launch` | ✓ |
| `map_amap_plus_force` | false | 广播控制高德时跳过接收者探测，强制发送（修改包用动态注册时开） | ✓ |
| `map_amap_plus_zero` | false | 广播控制高德时 x/y/w/h 全传 0（位置由高德自己记） | ✓ |
| `map_app` | "" | 地图应用包名 | ✓ |
| `map_app_label` | "" | 地图应用显示名（仅展示） | ✓ |
| `map_auto_start` | false | 进桌面自动拉起地图 | ✓ |
| `map_return_delay` | "3" | 拉起后延时回桌面秒数（1–10） | ✓ |
| `map_border` | true | 地图区描边 | ✓ |
| `map_border_color` | #4C8DFF | 描边颜色 | ✓ |
| `map_day_night_sync` | — | 未接 | ✗ |

**迁移逻辑**（`SettingsStore.init`）：只把历史值 `floating` 迁到 `freeform`。**不要**再顺手迁 `freeform`——它是现行有效值，迁走会导致设置反复被改回内嵌。

**其它硬编码常量**：

| 位置 | 值 |
|---|---|
| `MusicController.MD3_PKG` | `com.md3music.md3music` |
| `MusicController.MD3_SERVICE` | `com.md3music.md3music.MD3MusicMediaSessionService` |
| `MusicController.EXTRA_LYRIC_INFO` | `lyricInfo` |
| `MapWidgetClient.AMAP_PKG` / `ACTION` | `com.autonavi.amapauto` / `com.similar.widget.view.service` |
| `MapHeaderOverlay.HEADER_HEIGHT_DP` | 43f（实测系统标题栏 73px@density1.75） |
| `AppListFragment.SETTINGS_ENTRY` | `__settings__` |
| `HomeFragment.PLACEHOLDER` | -100（无歌词占位态标记） |

---

## 6. 视觉契约（重做卡片页时必须保持的）

### 6.1 颜色（`values/colors.xml`）

```
bg_base       #0B0D10   页面底色
bg_scrim      #66000000
card_bg       #CC161A1F 卡片（半透明；刻意不用模糊，MT8666 叠模糊会掉帧）
card_bg_solid #1A1D22
card_stroke   #1FFFFFFF
text_primary  #F2F4F7
text_secondary#98A0AE
text_tertiary #5F6673
accent        #4C8DFF   强调/播放键
accent_soft   #334C8DFF
```

### 6.2 尺寸策略（**这是重点，别退回固定 dp**）

- **右栏宽度**：`layout_constraintWidth_percent="0.22"`（原版 420/1920 与 280/1280 都正好 21.9%）
- **卡片高度**：`LinearLayout` + `layout_weight`，时间卡固定 `time_card_height`（68/96dp），其余 **52 : 19 : 18**
- **封面 / 播放键 / 上下曲**：`heightPercent` + `ratio 1:1`（0.24 / 0.17 / 0.13，带最小 44/38/30dp）
- **歌词行**：4 行等分歌词区（weight）

实测三种可用高度都自洽：535dp（1097×617）/ 681dp（1280×720）/ 1022dp（1920×1080）。
原版写死 534dp 音乐卡的方案在 617dp 屏上会直接溢出——**这是必须保留的修正**。

### 6.3 不可破坏的 id 契约

代码用 `findViewById` 直接拿，改布局**不能改这些 id**：

```
map_host / map_hint_title / map_hint_desc / home_theme_host
time_text / time_ampm / date_text / lunar_text
monitor_cpu / monitor_mem / monitor_temp
album_cover / album_art / music_title / music_artist
lyric_view / lyric_line_0..3
music_progress / music_current_time / music_total_time
btn_play / btn_prev / btn_next
time_date_card / music_card / travel_card / monitor_card
```

---

## 7. 已知问题与技术债

| # | 问题 | 影响 | 建议 |
|---|---|---|---|
| 1 | **视觉是 M0 骨架级**：纯色卡 + 细描边，没有层次、没有品牌感 | 这就是要重做的部分 | 见第 8 节 |
| 2 | 行程卡/瞬时功耗的**数据层已就位，但实车映射未确认**（模拟器恒 `--`） | 车机上看不到数字 | 实车跑「设置 → 车辆 → 打印车辆诊断」，按 `VehicleProbe` 输出补映射或 AIDL 客户端 |
| 2b | 歌词依赖「重连补拉」（最多 4 次 / 11 秒） | 首次显示晚 0.3~1 秒 | 对方补推后才能去掉；属音乐应用侧行为 |
| 3 | `README.md` 说「内嵌地图未完成」，实际代码已有 `MapEmbedView`，但需系统权限 | 文档误导 | 以本文档为准 |
| 4 | 内嵌地图受系统权限限制，普通安装必然走自由窗口兜底，会弹一次「内嵌不可用」Toast | 用户困惑 | 默认值已改 freeform；`fallbackDone` 保证只弹一次 |
| 5 | 自由窗口顶部有系统标题栏，只能靠 `MapHeaderOverlay` 盖住 | 不授权就直接露系统栏 | 保持现状，或干脆不追求「无系统栏」 |
| 6 | 双页常驻，切到应用页后音乐 150ms 循环仍在跑 | 轻微耗电 | 可在 `onPause` 停 `musicTick` |
| 7 | `MapWidgetClient` / `MapProbeActivity` / `frida-kit` 都是死代码 | 增加理解成本 | 重做时可整体删除 |
| 8 | `AUTO_START`、`DAY_NIGHT`、`THEME`、`MUSIC_VINYL` 四项 `ready=false`，UI 显示「待接入」 | 设置页有 4 个灰项 | 要么实现，要么从 Spec 里删掉 |
| 9 | ~~顶部状态栏文案写死「M0 · 骨架」~~ 已在 0.9.0-m10 换成瞬时功耗 | — | — |
| 10 | 无签名配置，只有 debug 包 | 无法覆盖安装正式版 | 需要时加 `signingConfigs` |

---

## 8. 重做卡片页的切入点

### 8.1 改哪里

| 目标 | 改动点 |
|---|---|
| 换卡片视觉（圆角、阴影、层次、材质） | `values/colors.xml` + `values/dimens.xml` + `drawable/bg_card.xml` + 各 `include_*_card.xml` |
| 重排卡片（换位置、换比例、加卡减卡） | `home_theme_classic.xml`（weight + 百分比）+ `HomeFragment.applySettings()` 的显隐表 + `SettingsSpec` 卡片组 |
| 换整套首页主题 | 新增 `home_theme_xxx.xml`，在 `HomeFragment.onViewCreated` 里按 `Keys.THEME` 选 layout id（当前是写死 classic） |
| 改地图区形态 | `fragment_home.xml` 的 `map_host` + `HomeFragment.applyMap()` |

### 8.2 三条硬约束

1. **id 不能改**（见 6.3），否则 `findViewById` 全断
2. **高度不能写死 dp**（见 6.2），三种分辨率要都活
3. **`home_theme_host` 这层间接不能去掉**——它是换肤的唯一挂点

### 8.3 可以放心复用的部分

- `MusicController` + `LyricInfo`：接口只有 `snapshot()` / `positionMs()` / `playPause()` / `previous()` / `next()`，与布局完全解耦
- `SettingsStore` / `SettingItem` / `SettingsAdapter`：加设置项只需在 `SettingsSpec` 加一行 + `Keys` 加一个常量
- `LauncherActivity` 的 HOME 接管、全屏、双页骨架
- `AppRepository`

### 8.4 装机验证

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell cmd package set-home-activity com.lf.launcher/.LauncherActivity   # 车机权限不足时用 su -p -c "..."
adb shell input keyevent KEYCODE_HOME
```

悬浮窗授权（盖系统标题栏用，可选）：

```bash
adb shell appops set com.lf.launcher SYSTEM_ALERT_WINDOW allow
adb shell appops set com.autonavi.amapauto SYSTEM_ALERT_WINDOW allow   # 高德自己建浮窗也要
```

---

## 9. 一页速查

```
进桌面         LauncherActivity → ViewPager[0] HomeFragment
首页结构       home_theme_host → home_theme_classic → 左地图 + 右四卡
卡片显隐       SettingsStore 的 CARD_*，HomeFragment.applySettings()
时钟           5s tick；音乐 150ms tick；状态栏 15s tick
地图           默认 freeform（系统浮窗铺在 map_host 位置）
设置           AppListFragment 第一格 → SettingsActivity → SettingsSpec 27 项
换肤挂点       R.id.home_theme_host
构建           $GRADLE :app:assembleDebug（JAVA_HOME=JDK21）
产物           app/build/outputs/apk/debug/app-debug.apk
```
