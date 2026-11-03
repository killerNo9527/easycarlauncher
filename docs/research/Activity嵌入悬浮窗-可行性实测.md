# 把地图应用的 Activity 当 View 塞进悬浮窗 —— 可行性实测

> 最小 Demo：`app/src/main/java/com/lf/launcher/probe/`
> 设备：emulator-5554 · Android 9 / API 28 · 1920×1080 @ 280dpi · 高德车机版 `com.autonavi.amapauto`

## 一、结论

**不可行。** 而且不行的原因不是「权限没给够」，是**这个动作在 Android 上根本不存在对应的 API**。

三句话讲完：

1. `SYSTEM_ALERT_WINDOW` 只决定「我的窗口能不能画在别人上面」，它跟「别人的 Activity 变成我的 View」没有任何关系，给了也白给。
2. `Activity` 不是 `View`，`View` 不能跨进程传，所以「把别人的 Activity 当 View 拿过来」在类型层面就不成立。
3. 唯一能实现「画面进来 + 触摸回去」的机制是 VirtualDisplay + setLaunchDisplayId + 输入注入 —— AOSP 的 CarLauncher 就是这么做的，还把它封装成了 `ActivityView`。前两步要 `CAPTURE_VIDEO_OUTPUT` / `MANAGE_ACTIVITY_STACKS`（`signature|privileged`，装成 priv-app 能拿），第三步要 `INJECT_EVENTS`（纯 `signature`，必须 platform 签名）。

## 二、Demo 怎么跑

桌面 → 设置 → 地图分组 → **「嵌入可行性探针（Demo）」**，或直接：

```bash
adb shell am start -n com.lf.launcher/.probe.EmbedProbeDemoActivity
# 车机砍掉「显示在其他应用上层」页面时，直接授权
adb shell appops set com.lf.launcher SYSTEM_ALERT_WINDOW allow
```

界面左边是五档探测的逐条结果，右边是**一个真的悬浮窗**（`TYPE_APPLICATION_OVERLAY`，实测 `880×842 @(998,196)`，`appop=SYSTEM_ALERT_WINDOW`），里面只有一块 SurfaceView —— 那就是「地图本该在的地方」。它一直是黑的，就是答案。

## 三、五档实测（原始输出）

| # | 探测 | 结果 |
|---|---|---|
| ① | `Activity` 是 `View` 吗 | ✗ `View.isAssignableFrom(Activity)` = **false** |
| ② | `ActivityView`（AOSP CarLauncher 用的容器） | ✗ 能构造、能 addView，但启动时抛 `IllegalStateException: Trying to start activity before ActivityView is ready.` |
| ③ | `createVirtualDisplay(VIRTUAL_DISPLAY_FLAG_PUBLIC)` | ✗ SecurityException（原文见下） |
| ④ | 私有虚拟屏 + `setLaunchDisplayId` 拉高德 | ✗ 屏建成功，但启动被 Permission Denial |
| ⑤ | `View` 能跨进程传吗 | ✗ 不实现 `Parcelable` |

### ③ 的原始异常

```
java.lang.SecurityException: Requires CAPTURE_VIDEO_OUTPUT or
CAPTURE_SECURE_VIDEO_OUTPUT permission, or an appropriate MediaProjection token
in order to create a screen sharing virtual display.
    at com.android.server.display.DisplayManagerService$BinderService
       .createVirtualDisplay(DisplayManagerService.java:1885)
```

注意最后半句 **"or an appropriate MediaProjection token"** —— 这正是 MediaProjection 抓屏能过、普通应用过不了的分界线。

### ④ 的原始异常（最关键的一条）

```
displayId=1 建成功，但 startActivity 被拒：
java.lang.SecurityException: Permission Denial: starting Intent
  { act=android.intent.action.MAIN
    cat=[android.intent.category.LAUNCHER]
    flg=0x10000000
    pkg=com.autonavi.amapauto
    cmp=com.autonavi.amapauto/com.autonavi.amapauto.MainMapActivity }
  from ProcessRecord{... com.lf.launcher/u0a50} (pid=8210, uid=10050)
  with launchDisplayId=1
```

拆开看：

- **私有虚拟屏建成功了**（`displayId=1`）—— 普通应用可以给自己造一块屏，这一步不要权限；
- **但把别人的 Activity 拉到这块屏上被拒**，拒绝理由直接写着 `with launchDisplayId=1`。

也就是说：屏你能造，应用你拉不上去。因为那块屏不是 **trusted display**，`ADD_TRUSTED_DISPLAY` 是签名级。

### ② 的一处更正（重要）

这份文档第一版在这里写错了，现已更正：

- **错**：以为 `ActivityView` 是 API 29 才有，本机加载到它说明是"空壳"。
- **对**：`android.app.ActivityView` **Android 9 (Pie) 就已经存在**，只是标了 `@hide`。
  Android 9 的 API 形状是 `setCallback(StateCallback)` + `startActivity(Intent)` /
  `startActivity(PendingIntent)`，**从来没有 `setActivity`**（第一版找错了方法名）。

按正确 API 跑一遍：

```
API: release() / startActivity(Intent) / startActivity(PendingIntent)
StateCallback: 反射读不到方法（@hide 被过滤），
               源码里是 onActivityViewReady / onActivityViewDestroyed / onTaskMovedToFront
✓ 类存在、构造成功（@hide 但反射能到）
✓ addView 成功
✗ startActivity(Intent) → IllegalStateException:
                            Trying to start activity before ActivityView is ready.
```

这句 `IllegalStateException` 出自 `ActivityView.prepareActivityOptions()`，
原因是内部 `initVirtualDisplay()` 没成功 —— 也就是 ③ 那个 `SecurityException`。
链条详见 `AOSP-CarLauncher路径拆解.md`。

## 四、为什么 `SYSTEM_ALERT_WINDOW` 帮不上忙

它是 **appop**，管的是 z-order 独占的一层画布：

```
SYSTEM_ALERT_WINDOW  →  我能 addView 一个窗口，盖在别人上面
                     ✗  别人应用的内容不会因此出现在我的窗口里
                     ✗  别人的触摸事件不会因此转发给我
```

你的悬浮窗和地图的窗口是**两个互不相干的系统窗口**，都直接挂在 WindowManager 上。你能做的只有「把自己的窗口摆在它上面」，摆不出「它的一部分」。

这也是为什么上一轮做覆盖层时，只能画一个演示图层，或者用系统自由窗口把高德摆在卡片位置 —— 后者能画能点，但它是**独立任务**，切页不会跟着走，还带系统标题栏。

## 五、那什么可行

| 方案 | 画面 | 触摸 | 代价 | 本机可行 |
|---|---|---|---|---|
| **A. 地图 SDK 自绘**（高德/百度 MapView） | 自己的 View，实时 | 原生 | 需要地图 SDK 的车机授权 key；UI 是自己画的，不是高德车机版的完整界面 | ✅ 唯一推荐 |
| **B. MediaProjection 抓屏** | 真镜像 | ✗ 只读 | 每次启动用户要点「开始录制」；录的是整屏不是单个应用 | ⚠️ 能看不能碰 |
| **C. VirtualDisplay + 注入** | 真镜像 | ✅ | 三项 signature 权限；即使有权限，还要自己做坐标映射、多指、旋转、返回键注入 | ❌ 本次实测被拒 |
| ~~D. Activity 当 View~~ | — | — | API 存在（`ActivityView`），但创建就要 `INJECT_EVENTS`，启动还要容器 ready | ❌ 就是本 Demo 证否的 |

**A 是唯一在普通安装下能同时满足「嵌在卡片里 / 切页即走 / 真交互」的路**，因为地图就是你自己的 View，从始至终没离开过你的 View 树。代价是不能复用高德车机版那套完整 UI，得用 SDK 自己搭。

如果你要的恰恰是「高德车机版完整界面，还能拖」，那只有 C，而 C 在原厂车机上靠的是**预装特权**（氢桌面随 ROM 进 `/system/priv-app/`），普通安装拿不到。三条自查命令：

```bash
adb shell pm path com.mcar.auto          # 在 /system/priv-app/ 下 = 预装特权应用
adb shell dumpsys package com.mcar.auto | grep -E "CAPTURE_VIDEO_OUTPUT|INJECT_EVENTS|MANAGE_ACTIVITY_STACKS" -A1
adb shell "ps -A | grep -i frida"        # 有进程 = 运行时注入
```

## 六、代码在哪

| 文件 | 作用 |
|---|---|
| `probe/EmbedProbeDemoActivity.kt` | Demo 页面：权限申请、日志渲染、按钮 |
| `probe/EmbedProbe.kt` | 五档探测，纯 API 调用，异常原样返回 |
| `probe/ProbeOverlay.kt` | 真的 `TYPE_APPLICATION_OVERLAY` 悬浮窗 + SurfaceView |

`EmbedProbe.runAll(context, surface, includeStep4)` 可以直接被别的页面复用；`ProbeOverlay` 的窗口参数（`NOT_FOCUSABLE | NOT_TOUCHABLE`、outline 裁圆角）跟主工程 `MapOverlayWindow` 同一套思路。
