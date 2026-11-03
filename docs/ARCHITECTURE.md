# 架构与实现要点

这份文档只讲**为什么这样写**，以及"换成另一种写法会坏在哪"。改动前值得先读一遍，很多结论是踩过坑才得到的。

文件清单看 [README](../README.md#目录结构)，使用方法看 [USAGE](USAGE.md)。

---

## 1. 平台背景

| 项 | 值 | 影响 |
|---|---|---|
| 目标设备 | MT8666 / 6GB / 1920×1080 / Android 9 | 全部设计围绕它，其他车机需要自己验证 |
| `minSdk` / `targetSdk` | 28 / **28** | 见下 |
| 语言 | Kotlin 2.0 / 原生 View | 无 Compose：车机 GPU 弱，Compose 首帧和滑动都比 View 慢 |
| 依赖 | 7 个 AndroidX + Media3 | 无网络库、无图片库、无 DI 框架 |

**`targetSdk` 停在 28 是刻意的。** 抬到 29+ 会立刻引入三件事：后台启动 Activity 受限（地图回桌面那条路直接断）、分区存储（壁纸与缓存路径要改）、`MediaProjection` 需要前台服务。这些在车机上全是负收益。代价是放弃新 API 的部分行为，对桌面这种常驻应用可以接受。

---

## 2. 运行时结构

```
LauncherActivity        HOME / singleTask / 横屏锁定 / 全屏沉浸
├── wallpaper_bg        读系统壁纸，失败降级渐变
├── view_pager          ViewPager2，2 页，offscreenPageLimit=1
│   ├── [0] HomeFragment
│   │     └── home_theme_host → inflate home_theme_classic
│   │           ├── map_host           地图区（点击拉起地图，长按进设置）
│   │           └── right_container_widgets
│   │                 ├── 时间日期卡   固定高
│   │                 ├── 音乐卡       weight
│   │                 ├── 负载卡       weight
│   │                 └── 天气卡       weight
│   └── [1] AppListFragment            网格，第一格 = 桌面设置
├── statusBar           自定义顶栏（时间居中 + 快捷应用 + 四图标）
└── bottomBar          底栏（封面 + 三键 + 三个导航入口），可关
```

**双页都常驻**（`offscreenPageLimit = 1`）。所以切到应用页后，音乐卡的 150ms 刷新循环仍在跑。这是有意的：切回首页时进度条和歌词不能有跳变。代价是轻微后台耗电。

### 刷新节奏

| 循环 | 间隔 | 内容 |
|---|---|---|
| `tick` | 5s | 时钟、负载（CPU / 内存 / 温度） |
| `musicTick` | 150ms | 音乐元数据、进度、歌词行 |
| 状态栏时间 | 15s | 与系统分钟对齐 |

进度**不是**每次拉回来更新，而是用 `PlaybackClock` 在两次回调之间插值，否则 150ms 的抖动肉眼可见。

---

## 3. 尺寸体系：不写死像素

这是整个项目最容易退化的部分。三种可用高度都必须活：**617 / 720 / 1022dp**。

| 元素 | 方式 |
|---|---|
| 右栏宽度 | `layout_constraintWidth_percent="0.22"`（原版 420/1920 与 280/1280 都正好 21.9%） |
| 卡片高度 | `LinearLayout` + `layout_weight`，时间卡固定高，其余分剩余空间 |
| 封面 / 播放键 / 上下曲 | `heightPercent + ratio 1:1`，带最小尺寸兜底 |
| 歌词行 | 按歌词区高度分 3 / 5 / 7 行，等分 weight |
| 字号 / 留白 | 两档 `dimen`：`values/`（宽 < 1400dp）与 `values-w1400dp/` |

**不要退回固定 dp。** 原厂桌面把音乐卡写死 534dp，四卡加起来 846dp，在 617dp 屏上直接溢出。这是当初必须重写尺寸策略的直接原因。

分档只放**字号和栅格**。凡是能用 weight 或百分比表达的高度，都不要进 dimen——否则每换一个分辨率就要重调一次。

---

## 4. 音乐与歌词

### 必须用 Media3，不能用 framework

歌词放在 `MediaItem.mediaMetadata.extras["lyricInfo"]` 里。而 `android.media.MediaMetadata`（framework）**没有 extras 字段** —— 只有 androidx 的 `MediaMetadataCompat` / Media3 的 `MediaMetadata` 才有。

所以走 framework 的 `android.media.session.MediaController` 只能拿到歌名、歌手、封面、进度，**歌词这一层被吞掉**，表现为"音乐卡有歌名但永远暂无歌词"。

换成 `androidx.media3:media3-session` 的 `MediaController` 后，`onMediaMetadataChanged` 给出的对象带 extras，一步到位。不需要 root，也不需要通知使用权。

### 歌词转发的两个前提

1. 播放器是 md3Music（`com.md3music.md3music`）
2. 它的设置里打开了「歌词转发」（`settings_lyric_info_enabled`）

这是播放器侧行为，桌面无法绕过。想接别的播放器，得那个播放器也往 extras 里写歌词。

### 长歌词为什么自己画

一句歌词超出宽度时，标准做法是 `ellipsize` 截断或 `marquee` 跑马灯，但需求是"在停留时间内扫到句尾停在句尾"，两者都做不到。

第一版用了 `super.onDraw` + `canvas.translate`，在模拟器正常、**车机上只在中间开一扇窗**——因为画什么、画在哪取决于 TextView 内部的 Layout（`VERY_WIDE`、ellipsize、gravity、自裁剪各插一手）。最终改成溢出时自己 `canvas.drawText`：测量和绘制用同一个 Paint、同一个字符串，不依赖排版。

另外，滚动时长必须由"这句还能显示多久"下发（`setSweepBudget()`）。来回滚一趟要 8.8 秒，而一句歌词只停留 2~3 秒，早期版本根本扫不到句尾就换句了。

---

## 5. 地图：三条通道，两条已被证伪

这是试错最多的部分。**以下结论不必重走**：

| 通道 | 机制 | 结论 |
|---|---|---|
| **广播控制修改版高德** | `com.autonavi.plus.showmap` / `closemap` + `x/y/w/h` extras | 可用，但**官方包不认**。必须先探测接收者再发，否则空放广播 |
| **全屏启动 + 延时回桌面** | `startActivity` → 2s 后把自己拉回前台 | 可用，兜底方案。官方地图没有悬浮窗，回桌面后桌面会盖住它 |
| 遮罩 / 描边 | `MapMaskOverlay` 在地图区画描边 | 可用 |

已证伪，不要再试：

1. **高德 `UsbFillActivity` 不建浮窗**。它只是启动器 + 崩溃自修复页，权限框判的是存储权限，与悬浮窗无关。
2. **高德真正的浮窗是 `com.autonavi.autofloat.FloatWindowService`**（`type=2038`），但**清单未导出**，普通应用起不来；root 强开出来的是车速/限速小窗，不是地图卡。
3. **`AutoSimilarWidgetService` 的 AIDL `addSurface`**：返回 true 但画面永远不出来，Surface 得由高德自己驱动。
4. **内嵌虚拟屏**：`VirtualDisplay` + `setLaunchDisplayId` 需要系统签名权限（`CAPTURE_VIDEO_OUTPUT` / `ADD_TRUSTED_DISPLAY` / `MANAGE_ACTIVITY_STACKS` / `INJECT_EVENTS`），普通安装必然 Permission Denial。

**结论：普通第三方 Launcher 在高德车机版上没有"把地图浮进卡片"的可用入口。** 广播是唯一可行的那条，而且要求对方是愿意读 extras 的修改版。

### 自动拉起地图的三个保护

`scheduleAutoMap()` 里叠了三层，缺一个就会出问题：

- **400ms 延迟**：等 `onResume` 完全稳定再启动，否则会和窗口动画抢焦点
- **2 秒回桌面**：`onPause` 后开始计时（不是启动后），这样导航真正起来才计时
- **15s 冷却 + 4s 看门狗**：防止"回桌面 → 又拉起"死循环

---

## 6. 状态栏

### 时钟居中不能用 weight

用 `weight` 占位把时钟推到中间，只会推到"剩余空间的中点"，而左右两侧内容宽度不等，所以永远偏。正确做法是 `FrameLayout` + `layout_gravity="center"`，让时钟相对整条状态栏居中，与两侧内容无关。

### 三态图标不能用 alpha 压暗

"关闭"态是图标本体调浅 + 一个红叉。第一版用 `view.alpha` 整体压暗，结果**红叉也一起被压浅**，看起来像半透明的灰叉而不是警告。

现在三态各自一张 VectorDrawable，明暗和颜色都写在 XML 里。代价是 6 个文件（WiFi 3 态 + 蓝牙 3 态），换来不依赖运行时改色。

顺带一个坑：`ImageView` 上挂 `app:tint` 会把红叉一起染色，所以不能对整个 ImageView 染色。

### 快捷应用的重建策略

`renderStatusBar()` 每次都重建图标排。为了不闪，用 `shortcutsSignature`（包名列表拼成的字符串）比对，没变就直接返回。

---

## 7. 卡片排序

实现是 `removeAllViews()` + `addView()` 重排，不是改 `layoutParams` 顺序。关键点：**`layout_weight` 是挂在子 View 自身的 `LinearLayout.LayoutParams` 上的**，`removeView` 不会清掉它，所以重新 add 之后权重依然有效，不用重新设置。

顺序没变时直接返回，不重排——否则每次 `onResume` 都会闪一下。

---

## 8. 均衡器

### 挂在哪里

`DynamicsProcessing` 挂在 **session 0**（全局输出混音）上，所以所有 App 的声音都会过它。代价与限制：

- **控制权排他**：文档明确"If priority is higher than the priority used by the current effect engine owner, the control will be transferred"。所以桌面 EQ 和 CarEQ 同时开，后开的会抢走前一个。
- **官方对这条路的态度是 deprecated**：`WARNING: attaching a DynamicsProcessing to global output mix is deprecated!`。能用，但不是长期支持的用法。
- **不覆盖硬件通路**：收音机是独立硬件通路，不经过 Android 混音。

### `cutoffFrequency` 是上边界，不是中心频率

这是一个真实踩过的 bug。`DynamicsProcessing.EqBand` 的 `cutoffFrequency` 语义是**该频段的上边界**，第 i 段实际作用于 `[上一段 cutoff, 本段 cutoff]`。

早期把中心频率（20、25、31.5……20000）直接当上边界传进去，后果是"800Hz 那档 +3dB"实际落在 `[630, 800]` 上，比预期低半个档位，误差往低频累积：第一段 `cutoff=20Hz` 等于只作用于 20Hz 以下，几乎没有可闻影响。

修法：上边界取本段中心与下一段中心的**几何中点**。验证方式是看回读日志，`mid=#15` 从 `630Hz` 变成 `710Hz`（= √(630×800)）。

### 拖动回调必须回写数组

`EqEditorView.gains` 在赋值时做了 `copyOf()`，所以它内部是一份**独立副本**。拖动时它改自己的数组，通过回调把 `(index, gainDb)` 传出来。

如果接收端写成 `{ _, _ -> ... }` 把参数丢掉（这是初版的样子），完整后果链是：

1. 推子拉到 +12，界面显示 12（副本是对的）
2. 下发到 effect 的是旧 `gains` → 没有声音变化
3. 松手 `persist()` 存的也是旧值 → 文件里是一串 0

表现就是"界面明明拉到顶，却完全没反应，而且配置文件里是 0"。修法一行：回调里 `gains[index] = gainDb`。

### 验证方法只有一个

界面显示、`dumpsys media.audio_flinger` 里的 effect 链、readback 值，**都不能证明声音变了**。唯一可靠的是把 62Hz 推到 +12，用耳朵听低音有没有变厚。

排查时记住 `State=003` 是 `EFFECT_STATE_STARTED`（已启用），`000` 是未启用——AOSP 里这两个值是十六进制字符串。

---

## 9. 天气

### 和风直连的几个硬约束

| 约束 | 后果 |
|---|---|
| **坐标是「经度,纬度」** | 写反了会查到海里，而且不报错 |
| **新版账号必须用专属 API Host** | 形如 `abc.re.qweatherapi.com`。用 `devapi` / `api` / `geoapi` 一律 `Invalid Host` |
| **新版错误体没有 `code` 字段** | 变成 `{"error":{"type":"...#invalid-host","title":"Invalid Host"}}`。只读 `code` 的程序会把 403 显示成"HTTP 403"，看不出真正原因 |
| `/v7/weather/now` 不接受中文城市名 | 必须先用 GeoAPI 换成 LocationID，或者直接给坐标 |

### 地名提升为市

按坐标反查经常落到"区"一级（天安门 → 东城），但卡片上写"北京"比"东城"更符合直觉。所以坐标模式下优先用 `adm2`（上级市），手填城市名则原样使用（不擅自改用户的意思）。

和风**没有提供行政级别标记**：`type` 一律写 `city`，`rank` 也不按级别排序（东城 35、越秀 25、昆山 23、平遥 33），地名里的"市/县/区"后缀又被统一去掉。所以无法可靠区分"昆山（县级市）"和"越秀（市辖区）"，只能靠前面的市名消歧。

二级地名（"枣庄 市中"）是**两个 TextView**，不是拼成一个字符串。拼在一起用同样字号，读起来像两个并列的地名。

空间不足时**先省略市名、不截断区名**——用户真正想看的是区。

### JWT 的 Ed25519 为什么自己写

Android 直到 **API 33** 才支持 `KeyFactory.getInstance("Ed25519")`。目标车机是 API 28，调用直接抛 `NoSuchAlgorithmException`。

所以用纯 Java 按 RFC 8032 实现了曲线运算（BigInteger），单次签名 28ms。已验证：

- RFC 8032 §7.1 的三条测试向量
- 与 Node / OpenSSL 交叉双签 12 组随机密钥
- 本机假和风服务用 `crypto.verify` 真验签 App 发出的 token

另外 `exp` 取 900s 减 30s（实际 871s），防止车机时钟漂移导致 token 一生成就过期。

### 手机导入页

车机上打 32 位 Key 或整段 PEM 不现实，所以起一个临时 HTTP 服务：

- `ServerSocket` 绑随机端口，页面显示 IP:Port 和 4 位 PIN
- **根路径返回 404**，必须带正确 PIN 才能进表单
- **2 分钟过期**
- 表单留空的字段 = 不改动已存值（这条很重要：早期版本留空会把已存的 API Host 清成空，然后静默回退 `devapi`，表现为 403，极难排查）

局域网明文 HTTP，只在配置那两分钟开着。文档里明确提醒不要在公共 WiFi 下用。

---

## 10. 不可破坏的契约

### 布局 id

代码用 `findViewById` 直接取，改布局不能改这些 id：

```
map_host / home_theme_host
right_container_widgets
time_text / time_ampm / date_text / lunar_text
album_cover / album_art / music_title / music_artist
lyric_view
music_progress / music_current_time / music_total_time
btn_card_prev / btn_card_play / btn_card_next
weather_city / weather_district / weather_temp / weather_text / weather_range / weather_at
monitor_cpu / monitor_mem / monitor_temp
```

### `home_theme_host` 这层间接不能去掉

它是换肤的唯一挂点：`HomeFragment` 往 `home_theme_host` 里 inflate 主题布局。直接写在 `fragment_home.xml` 里会让"换一套首页布局"变成改代码而不是加文件。

### 广播接收必须带 data scheme

监听包变化（应用列表实时刷新）时，`IntentFilter` 必须 `addDataScheme("package")`，否则系统不会投递 `ACTION_PACKAGE_ADDED` 这类广播。

---

## 11. 技术债

诚实记录，不打算掩饰：

| 项 | 说明 |
|---|---|
| 无签名配置 | 只有 debug 包。长期使用建议自己生成 keystore |
| 主题只有一套 | `home_theme_classic` 是唯一实现，`home_theme_host` 这层准备好了但没用上 |
| 负载卡温度 | 扫 `thermal_zone*/temp` 取最大值，不同车机 zone 含义不同，可能取到电池而不是 SoC |
| 双页常驻耗电 | 切到应用页后音乐循环仍在跑，可以在 `onPause` 停掉，但会导致切回来时进度跳变 |
| 天气 Key 明文存储 | 存在 `SharedPreferences`，未加密。车机被 root 就能读出来 |
| 导入页是明文 HTTP | 仅局域网、2 分钟、带 PIN，但不是生产级安全方案 |

---

## 12. 历史档案

`docs/legacy/HANDOFF.md` 是开发中期的移交文档。其中**地图四通道、文件清单、配置项全表已经过时**（地图重构过、行程卡已删、文件数从 20 涨到 47），仅作历史记录保留。以本文档为准。
