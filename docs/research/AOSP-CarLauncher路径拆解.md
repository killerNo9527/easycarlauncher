# AOSP CarLauncher 那条路 —— 源码级拆解

> 参考：`platform/packages/apps/Car/Launcher`（pie-release）
> 核心：`frameworks/base/core/java/android/app/ActivityView.java`（pie-release）
> 实测：emulator-5554 · Android 9 / API 28 · 1920×1080 @ 280dpi

## 一、先纠正三个时代归属

你提的三件东西，不是一代的：

| 概念 | 真实情况 | 常见误解 |
|---|---|---|
| `android.app.ActivityView` | **Android 9 (Pie) 就有**，标 `@hide` | 以为 API 29 才有（我上一轮也说错了） |
| `ActivityView.StateCallback` | Android 9 已有 3 个方法 | 以为跟着 ActivityView 一起在 10 才有 |
| `ActivityOptions.setLaunchDisplayId` | API 26 (O)，**公开 API**，本身不要权限 | 以为它需要特权 |

所以本机能 `Class.forName("android.app.ActivityView")` 成功，不是模拟器抽风 —— 它确实在那儿，只是没进公开 API 表。

## 二、CarLauncher 怎么用它

布局里就是一个普通 View：

```xml
<androidx.cardview.widget.CardView ...>
    <android.car.app.CarActivityView
        android:id="@+id/maps"
        android:layout_width="match_parent"
        android:layout_height="match_parent"/>
</androidx.cardview.widget.CardView>
```

`CarActivityView extends ActivityView`，主界面里设回调：

```java
mActivityView = findViewById(R.id.maps);
mActivityView.setCallback(mActivityViewCallback);

private final ActivityView.StateCallback mActivityViewCallback =
        new ActivityView.StateCallback() {
            @Override
            public void onActivityViewReady(ActivityView view) {
                mActivityViewReady = true;
                startMapsInActivityView();          // ← 只在 ready 之后才敢启动
            }
            @Override
            public void onActivityViewDestroyed(ActivityView view) { mActivityViewReady = false; }
            @Override
            public void onTaskMovedToFront(...) {
                // 把 Launcher 自己 moveTaskToFront 拉回前台
            }
        };
```

最后一步才是启动：

```java
mActivityView.startActivity(getMapsIntent());
```

**注意 `onActivityViewReady` 这个回调是整个设计的支点** —— 它就是本文第三节那个 `initVirtualDisplay()` 成功的信号。

## 三、ActivityView 内部就三步

`ActivityView.java`（pie-release）里，`SurfaceView` 的 `surfaceCreated` 触发初始化：

```java
private void initVirtualDisplay() {
    final int width = mSurfaceView.getWidth();
    final int height = mSurfaceView.getHeight();
    final DisplayManager displayManager = mContext.getSystemService(DisplayManager.class);

    mVirtualDisplay = displayManager.createVirtualDisplay(
            DISPLAY_NAME + "@" + System.identityHashCode(this),
            width, height, getBaseDisplayDensity(), mSurface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);   // = 9

    final int displayId = mVirtualDisplay.getDisplay().getDisplayId();

    final IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
    wm.dontOverrideDisplayInfo(displayId);                                   // ②

    mInputForwarder = InputManager.getInstance().createInputForwarder(displayId);  // ③
}
```

启动时把 displayId 塞进 `ActivityOptions`：

```java
private ActivityOptions prepareActivityOptions() {
    if (mVirtualDisplay == null) {
        throw new IllegalStateException(
                "Trying to start activity before ActivityView is ready.");
    }
    final ActivityOptions options = ActivityOptions.makeBasic();
    options.setLaunchDisplayId(mVirtualDisplay.getDisplay().getDisplayId());
    return options;
}
```

触摸回传：

```java
@Override
public boolean onTouchEvent(MotionEvent event) {
    return injectInputEvent(event) || super.onTouchEvent(event);
}
private boolean injectInputEvent(InputEvent event) {
    if (mInputForwarder != null) return mInputForwarder.forwardEvent(event);
    return false;
}
```

所以你说的三件东西，**是同一个东西的三个零件**：

```
ActivityView  =  VirtualDisplay（画布）+ setLaunchDisplayId（把别人拉上去）+ InputForwarder（把触摸送回去）
```

你自己手搓 VirtualDisplay + setLaunchDisplayId（上一轮 ④ 干的事）本质上是**把它拆开来做**，只是少了第三块。

## 四、门槛在哪

### 4.1 源码里写死的

`ActivityView` 的类注释第一句：

```java
/**
 * Activity container that allows launching activities into itself and does input forwarding.
 * <p>Creation of this view is only allowed to callers who have
 * {@link android.Manifest.permission#INJECT_EVENTS} permission.
 * ...
 * @hide
 */
```

创建这个 View 本身就要 `INJECT_EVENTS`。

### 4.2 三个隐藏 API 调用

`dontOverrideDisplayInfo` / `createInputForwarder` / `getWindowManagerService` 都是 `@hide`，普通应用走 hidden API 会被拦。

### 4.3 启动落点由 AMS 判

官方文档 *Activity launch policy* 原文：

> To prevent a malicious app from misappropriating user-sensitive information by reading it from the surface of a virtual display that it has created, apps can launch their own activities only on a virtual display of their creation in Android 10. However:
> - System components with the `INTERNAL_SYSTEM_WINDOW` permission can launch on any display.
> - Callers with the `ACTIVITY_EMBEDDING` permission can launch activities from other apps that have `FLAG_ALLOW_EMBEDDED`.
> - **In Android 9 (and lower), restricted launch results throw SecurityException.**
> - Most security restrictions are applied in `ActivityStackSupervisor#isCallerAllowedToLaunchOnDisplay()`.

上一轮 ④ 收到的 `Permission Denial ... with launchDisplayId=1`，就是 `isCallerAllowedToLaunchOnDisplay()` 判的。

### 4.4 权限的真实保护级别（本机实测，`pm list permissions -f`）

**这里要更正上一轮的说法** —— 我说「三项全是 signature 级，priv-app 白名单也拿不到」，不准确：

| 权限 | protectionLevel（本机实测） | priv-app 白名单能拿吗 |
|---|---|---|
| `CAPTURE_VIDEO_OUTPUT` | **`signature\|privileged`** | ✅ 能 |
| `MANAGE_ACTIVITY_STACKS` | `signature\|privileged\|development` | ✅ 能 |
| `ACTIVITY_EMBEDDING` | `signature\|privileged\|development` | ✅ 能 |
| `INJECT_EVENTS` | **`signature`**（纯） | ❌ 不能，必须 platform 签名 |

这个差别是决定性的：

- **只装成 priv-app**（普通签名 + `/system/priv-app` + 白名单）→ 前三个都能拿到 →
  **VirtualDisplay 建得起来、高德也拉得上去 = 画面能进来**；
- 但 `createInputForwarder` 要 `INJECT_EVENTS`，纯 signature 拿不到 →
  **触摸回不去 = 只能看不能点**。

这也解释了市面上很多「车机分屏显示导航」的应用为什么点不动。

## 五、本机实测：链条闭合

Demo 里 ② 按 Android 9 的真实 API 形状跑了一遍（不是 `setActivity`，那个方法从来不存在）：

```
API: release() / startActivity(Intent) / startActivity(PendingIntent)
StateCallback: 反射读不到方法（@hide 被过滤），
               源码里是 onActivityViewReady / onActivityViewDestroyed / onTaskMovedToFront
✓ 类存在、构造成功（@hide 但反射能到）
✓ addView 成功
✗ startActivity(Intent) → IllegalStateException:
                            Trying to start activity before ActivityView is ready.
```

把四步串起来看：

```
startActivity 抛 IllegalStateException
   ← prepareActivityOptions() 里 mVirtualDisplay == null
   ← initVirtualDisplay() 没成功
   ← createVirtualDisplay(PUBLIC | OWN_CONTENT_ONLY) 抛 SecurityException
   ← ③ 实测：Requires CAPTURE_VIDEO_OUTPUT or ... MediaProjection token
```

**结论：CarLauncher 那个 `onActivityViewReady` 回调，在普通应用里永远不会触发。** 不是回调写错了，是它永远不会到。

## 六、这条路对你意味着什么

three 层门槛，越往下越硬：

| 你的部署方式 | 画得进来 | 点得动 | 依据 |
|---|---|---|---|
| 普通安装（当前） | ✗ | ✗ | ③④ 实测 |
| priv-app + 白名单，普通签名 | ✓ | ✗ | CAPTURE_VIDEO_OUTPUT / MANAGE_ACTIVITY_STACKS 是 privileged，INJECT_EVENTS 不是 |
| priv-app + **platform 签名** | ✓ | ✓ | 四项全拿 |

所以对氢桌面的判断可以更精确了：**它既然能点，就一定是 platform 签名**（或者 Frida 注入 system_server）。priv-app 只能解释「能画」。

三条自查命令：

```bash
adb shell pm path com.mcar.auto
# /system/priv-app/... 只是第一步

adb shell dumpsys package com.mcar.auto | grep -E "INJECT_EVENTS|CAPTURE_VIDEO_OUTPUT" -A1
# 这里能看到它到底有没有 INJECT_EVENTS。有 → platform 签名

adb shell "ps -A | grep -i frida"
# 有进程 → 走的是注入
```

## 七、真要接的话，前置条件

`ActivityView` 是 `@hide`，反射只能构造和 addView，**但 `StateCallback` 是抽象类，反射没法动态实现**（`Proxy` 只能代理接口）。所以想真正用它，必须：

1. 编译期依赖 framework 的隐藏符号：要么在 AOSP 树内编译，要么 `compileOnly files('libs/framework.jar')`（从目标 ROM 的 `/system/framework/framework.jar` 取）；
2. 应用装成 priv-app，并在 `/etc/permissions/privapp-permissions-*.xml` 里放行
   `CAPTURE_VIDEO_OUTPUT` / `MANAGE_ACTIVITY_STACKS`；
3. 要触摸，再加 platform 签名。

缺任何一条，都会退回到本 Demo 里那个 `IllegalStateException`。

---

**一句话**：你指的这条路，是 AOSP 给「系统桌面」写的实现，不是给第三方应用留的口子。它把 VirtualDisplay、setLaunchDisplayId、输入注入三件事打包成一个 `ViewGroup`，代码量很小，门槛全在权限上。
