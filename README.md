# native-window

> 类原生 Android 17 的 HyperOS 3 / Flyme 风格小窗。  
> 目标设备：**PixelOS 17 / LineageOS 17**（Android 17，API 37）。  
> 形态：**LSPosed + Root**，AGPL-3.0。

## 0.6.0-selftest：打开即自动测

0.5.0 的问题一直是“装了，但不知道哪一步没生效”。0.6.0 不再让用户点按钮：

- 打开主界面 280ms 后自动进入 `SelfTestActivity`；
- 自检页直接把自己的 task 交给 SystemUI 做一次 **freeform 往返**：`FREEFORM` → 观察窗口尺寸变小 → 恢复 `FULLSCREEN`；
- 结果页直接显示“核心链路通过/未通过”，并给出 LSPosed 作用域排查顺序；
- SystemUI 的接收器新增 `self_test=true` 白名单：只允许模块 UID 做这一次自检，普通 App 仍然不能改任务。

自检页不依赖 Launcher 菜单，也不自动拉起第三方 App；它测的是 system_server 门禁 + SystemUI WCT + task 实际尺寸，比重装一次再猜要直接。

完整说明见 [SELFTEST.md](SELFTEST.md)。当前静态冒烟 28/28；真机结果仍需在设备上跑。

## 0.5.0-log-dark 做了什么

0.3.0 已经接进真实 freeform，但 chrome 还是 App 悬浮窗；观感上一定是“胶囊先动、窗口后动”的两张皮。0.4.1 把 chrome 迁进 SystemUI 的原生 `CaptionWindowDecoration`，让胶囊和 Task surface 共用同一个 Surface：

| 层 | 0.4.1 实现 |
|---|---|
| 开窗 | Launcher 进程直接调用 `IActivityTaskManager.startActivityFromRecents(taskId, Bundle)`，Bundle 写 `android.activity.windowingMode=5`（FREEFORM）与 `android:activity.launchBounds` |
| 系统门禁 | system_server 在 `ActivityTaskManagerService#retrieveSettings` **之后** 打开 `mSupportsMultiWindow`、`mSupportsFreeformWindowManagement`、`mForceResizableActivities`、`mDevEnableNonResizableMultiWindow` |
| 原生 caption | 保留 `CaptionWindowDecorViewModel#shouldShowWindowDecor` 的 freeform 原生标题栏；Hook `WindowDecoration#inflateLayout`，在 `caption_window_decor` 根 View 上加胶囊 |
| 任务 chrome | `NativeCaptionDecorator` 隐藏 AOSP 三键、清掉标题栏底色，加 `NativeCapsuleView`；胶囊是 Task Surface 的子 View，随任务移动/裁剪/圆角 |
| 移动 / 缩放 | 拖动交给原生 `CaptionTouchEventListener`；按钮通过 `WindowContainerTransaction#setBounds` 调 `ShellTaskOrganizer#applyTransaction` |
| 迷你 / 全屏 / 关闭 | `setBounds` 到迷你尺寸；`setWindowingMode(FULLSCREEN)`；`IActivityTaskManager.removeTask(taskId)` |
| 最近任务菜单 | Hook `TaskOverlayFactory#getEnabledShortcuts`，追加 `RemoteActionShortcut`；PendingIntent 落到 **Launcher 进程** 的动态接收器，而不是模块 App |
| 动画 | `SpringAnimator` 自研半隐式欧拉弹簧；胶囊开窗、缩放、迷你切换都用它 |

### 0.5.0 新增：日志分享与深色模式

- **日志分享**：主界面「收集并分享日志」会抓 `logcat`（`NativeWindow*` / `LSPosed` / `AndroidRuntime` / `ActivityManager` / `WindowManager`）+ `dumpsys activity activities` + `dumpsys window windows` + `dumpsys activity processes`，写到 cache 后通过自己的 `ContentProvider` 分享，避免 `file://` 在 target 37 被拦。
- **root 兜底**：普通 App 没有 `READ_LOGS`；先跑普通命令，输出为空/权限拒绝时自动尝试 `su -c`，并记录 `root=true/false`，不会因为拿不到 dumpsys 就不给日志。
- **深色模式**：`Palette` 跟系统 `uiMode` 走；主界面日间浅灰、夜间近黑，状态栏/导航栏图标同步切换。预览的 Hyper / Flyme 皮肤只决定圆角和视觉语言，颜色跟随系统深色；原生 caption 胶囊也按 `UI_MODE_NIGHT` 切换深/浅。

## 从源码读出来的关键结论

这些不是猜的，是从 Android 17 源码里核对出来的；0.2.0 有两个会直接导致真机失败的硬错误。

### 1. `WINDOWING_MODE_FREEFORM` 是 5，不是 1

`android/app/WindowConfiguration.java`：

```java
public static final int WINDOWING_MODE_FREEFORM = 5;
```

0.2.0 的 `SmallWindowBridge` 把 `1` 当 freeform；`1` 实际是 `WINDOWING_MODE_FULLSCREEN`。结果就是“点了小窗，App 全屏”。0.3.0 已改成 `5`。

### 2. `startActivityFromRecents` 的第二个参数是 Bundle

`IActivityTaskManager.aidl`：

```java
int startActivityFromRecents(int taskId, in Bundle options);
```

0.2.0 反射 `startActivityFromRecents(int, ActivityOptions)` 会 `NoSuchMethodException`。0.3.0 改为：

```java
ActivityOptions options = ActivityOptions.makeBasic();
options.setLaunchBounds(bounds);
Bundle bundle = options.toBundle();
bundle.putInt("android.activity.windowingMode", 5);
bundle.putParcelable("android:activity.launchBounds", bounds);
```

### 3. freeform 开关必须在 `retrieveSettings` 之后改

`ActivityTaskManagerService#retrieveSettings` 会根据 config 重算：

```java
if ((supportsMultiWindow || forceResizable) && multiWindowFormEnabled) {
    mSupportsMultiWindow = true;
    mSupportsFreeformWindowManagement = freeformWindowManagement;
    ...
}
```

0.2.0 只挂构造和 `onSystemReady`，会被 `retrieveSettings` 覆盖。0.3.0 直接挂 `retrieveSettings(ContentResolver)` 的 after。

### 4. freeform 还要过 `TaskDisplayArea` 与 `Task.supportsFreeform`

`Task.supportsFreeformInDisplayArea` 要求：

```java
tda.isWindowingModeSupported(WINDOWING_MODE_FREEFORM,
        mAtmService.mSupportsMultiWindow,
        mAtmService.mSupportsFreeformWindowManagement,
        mAtmService.mSupportsPictureInPicture)
&& supportsMultiWindowInDisplayArea(tda)
```

非 resizeable 的国产 App 还要靠 `mForceResizableActivities` / `mDevEnableNonResizableMultiWindow` / `mSupportsNonResizableMultiWindow=1`，否则会被 letterbox 或拒绝。

### 5. AOSP 17 的 freeform caption 在 WMShell，不在 SystemUI 的 PhoneWindowManager

`com.android.wm.shell.windowdecor.CaptionWindowDecorViewModel#shouldShowWindowDecor`：

```java
if (taskInfo.getWindowingMode() == WINDOWING_MODE_FREEFORM) {
    return true;
}
```

它会给每个 freeform Task 画 `CaptionWindowDecoration`。0.4.1 不再关掉它，而是在它的 `caption_window_decor` 根 View 里放 HyperOS 胶囊；原生 caption 的圆角、阴影、拖动、crop 全部保留。

### 6. 任务边界修改的系统入口是 `ShellTaskOrganizer` + WCT

`ShellTaskOrganizer` 同时是 `TaskOrganizer`：

```java
public void applyTransaction(@NonNull WindowContainerTransaction t)
```

`WindowContainerTransaction#setBounds(WindowContainerToken, Rect)` / `setWindowingMode(WindowContainerToken, int)` 是移动、缩放、切全屏的底层入口。App 没有 token，也拿不到 `ShellTaskOrganizer`，所以这些动作必须在 SystemUI 进程做。

### 7. Pixel Launcher 没有可读源码，但类名在 Android 17 上仍存活

PixelOS manifest：

```xml
<project path="packages/apps/Launcher3" name="platform/packages/apps/Launcher3" />
<project path="vendor/pixel/launcher" name="PixelOS-AOSP/proprietary_vendor_pixel_launcher" />
```

`NexusLauncherRelease` 的 `Android.bp`：

```bp
android_app_import {
    name: "NexusLauncherRelease",
    overrides: ["Launcher3", "Launcher3QuickStep", "TrebuchetQuickStep"],
}
```

即 PixelOS 实际桌面是预编译 Pixel Launcher，**没有 Pixel Launcher 的 Java 源码**。但 `PixelLauncherEvolved` 在 Pixel 8 Pro / Android 17（`CP3A.260905.009`）上的 DEX 审计确认：

- `com.android.quickstep.TaskOverlayFactory` 仍存在；
- `com.android.launcher3.popup.RemoteActionShortcut` 仍存在；
- `TaskOverlayFactory$TaskOverlay`、`TaskShortcutFactory$2/$3.getShortcuts` 仍存在。

所以 0.3.0 先按类名 Hook；类名真的被 R8 改掉时，下一步接 DexKit 指纹，不假装已经支持。

### 8. Launcher 持有任务权限，模块 App 没有

`Launcher3/quickstep/AndroidManifest.xml`：

```xml
<uses-permission android:name="android.permission.START_TASKS_FROM_RECENTS"/>
<uses-permission android:name="android.permission.MANAGE_ACTIVITY_TASKS"/>
```

0.2.0 把 PendingIntent 发回模块 App 的 `OpenReceiver`，App 没有签名权限，真机必然被 `enforceCallingPermission` 拒绝。0.3.0 改成：

```text
TaskOverlayFactory 菜单
  → RemoteActionShortcut
  → PendingIntent（Launcher UID）
  → Launcher 进程的 TaskLaunchReceiver
  → SmallWindowBridge.startActivityFromRecents(taskId, bundle)
```

模块 App 只负责后续 chrome 服务，不再负责开窗。

## 当前进度

| 模块 | 状态 | 说明 |
|---|---|---|
| HyperOS 3 chrome 预览 | ✅ | 圆角、胶囊、底部把手、迷你 chip、Flyme 皮肤 |
| 自研弹簧动画 | ✅ | `SpringAnimator`：半隐式欧拉 + 4 子步，60/90/120/144Hz 一致 |
| 最近任务菜单 | ✅ 代码完成，待真机 | LOS / Pixel 同一套 `TaskOverlayFactory` Hook |
| 真实 freeform 开窗 | ✅ 代码完成，待真机 | `FREEFORM=5` + Bundle + Launcher 权限 |
| SystemUI 原生 caption | ✅ 代码完成，待真机 | 保留 AOSP caption，在里面塞项目胶囊 |
| WCT 移动/缩放 | ✅ 代码完成，待真机 | `ShellTaskOrganizer#applyTransaction` |
| 迷你/全屏/关闭 | ✅ 代码完成，待真机 | 原生胶囊按钮 → 改 bounds / 改 windowingMode / removeTask |
| Pixel Launcher DexKit 指纹 | ⏳ | 当前按类名，R8 改名后需要 DexKit |
| 多窗 / 分屏 / 桌面模式 | ❌ | 首版不做 |
| 日志分享 / root 抓取 | ✅ 代码完成 | 普通 logcat → su 兜底；Provider 分享 |
| 深色模式 | ✅ 代码完成 | 主界面、预览、原生胶囊跟随系统 uiMode |
| 自动自检页 | ✅ 代码完成，待真机 | 打开即测 freeform 往返，不需要点按钮 |
| 真机回归 | ❌ | 当前环境无 adb 设备、无 emulator |

## 目录

```text
native-window/
├── app/src/main/
│   ├── java/io/github/wumisc/nativewindow/
│   │   ├── MainActivity.java              # 设置 / 预览 / 日志入口
│   │   ├── OverlayService.java            # 预览悬浮窗
│   │   ├── DemoOverlay.java               # HyperOS/Flyme 视觉基线
│   │   ├── Palette.java                   # 日间/夜间配色
│   │   ├── log/
│   │   │   ├── LogCollector.java          # logcat + dumpsys + su 兜底
│   │   │   └── LogProvider.java           # content:// 日志分享
│   │   ├── anim/SpringAnimator.java       # 自研物理弹簧
│   │   └── hook/
│   │       ├── ModuleEntry.java           # LSPosed 入口
│   │       ├── SystemServerHook.java      # freeform 门禁 + 权限补丁
│   │       ├── SystemUiHook.java          # 原生 caption / TaskOrganizer / WCT
│   │       ├── NativeCaptionDecorator.java # 把胶囊塞进 caption 根 View
│   │       ├── NativeCapsuleView.java      # 胶囊视觉与按钮
│   │       ├── LauncherHook.java           # 最近任务菜单 + Launcher 接收器
│   │       └── SmallWindowBridge.java     # 正确签名的 freeform 启动
│   ├── res/
│   └── assets/xposed_init
├── libs/api-82.jar                        # compileOnly，不打包
├── scripts/build.sh                       # aapt2 → javac → d8 → zipalign → apksigner
└── dist/                                  # 构建产物（.gitignore）
```

## 构建

### 命令行（不需要 Gradle）

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export JAVA_HOME=/path/to/jdk-17
bash scripts/build.sh
# 输出：dist/native-window-0.6.0-selftest.apk
```

### Android Studio

JDK 17+、Android SDK Platform 37；AGP `9.5.3`，`compileSdk 37`、`minSdk 34`、`targetSdk 37`。

## 安装与作用域

1. 安装 APK；
2. 授予「显示在其他应用上层」（只用于下方视觉预览）；
3. LSPosed 启用模块，作用域必须勾选：
   - **系统框架**（`android`）：freeform 门禁、task 权限补丁；
   - **SystemUI**（`com.android.systemui`）：原生 caption、TaskOrganizer、WCT；
   - **桌面**（`com.android.launcher3` 或 `com.google.android.apps.nexuslauncher`）：最近任务菜单与开窗；
4. 重启 SystemUI / 设备；
5. 进最近任务，点任务菜单里的「小窗」；
6. 出问题回主界面点「收集并分享日志」，把 txt 发回来。普通 logcat 拿不到时会请求 root。

> PixelOS 上如果最近任务菜单没有出现「小窗」，先看 LSPosed 日志里 `NativeWindow/Launcher: TaskOverlayFactory not found` 还是 `RemoteActionShortcut not found`；这两种都是类名/混淆问题，不是开窗内核问题。

## 适配边界与已知限制

- **没有真机/模拟器**：本环境 `adb devices` 为空，0.5.0 是源码级实现 + 静态编译通过；真机回归还未做。
- **Pixel Launcher 没有源码**：只能按 Android 17 仍存活的类名 Hook；后续要接 DexKit 指纹和 `TaskShortcutFactory` 的 R8 名。
- **原生 caption 依赖 WMShell 类名**：`WindowDecoration#inflateLayout` 和 `caption_window_decor` 是 Android 17 的当前实现；如果 ROM 把 `caption_window_decor` 改名，胶囊不会出现，但 AOSP caption 仍在，不会把窗口做没。
- **没有命令接收器/悬浮窗 chrome**：0.5.0 的按钮全部在 SystemUI 进程内直接调 WCT；模块 App 不再持有任务操作入口，权限面比 0.3.0 小。
- **日志分享依赖 root 才能拿全**：普通 App 没有 `READ_LOGS`，没有 root 时只能拿到 `logcat` 自身权限允许的部分；`dumpsys` 多数需要 root。日志里会明确写 `root=false` 和失败原因。
- **迷你窗**：当前是“缩小 bounds”的迷你窗，不是把任务迁到 VirtualDisplay 的贴边保活；后者是 M3。
- **国产 App 非 resizeable**：已用 `mForceResizableActivities` 强制；个别 App 会重排布局或拒绝，需要 per-app 规则。
- **不复制 OEM 私有代码/素材**：不碰 ColorOS `OplusZoomWindowManager`、`FlexibleTaskController`、`getFlexibleZoomState`；不复制小米/魅族/OPPO 商标、系统图标、字体。

## Roadmap

- **M1（当前）**：PixelOS 17 / LOS 17 真机回归；freeform 开窗、原生 caption 胶囊、WCT 移动缩放。
- **M2**：DexKit 指纹，覆盖 Pixel Launcher R8 改名；`TaskShortcutFactory` 原生 freeform 菜单合并去重。
- **M3**：迷你/贴边真实任务迁移、贴边保活、VirtualDisplay 单槽回退。
- **M4**：侧边栏、底角扇形接到真实任务；最近任务滑动方向、关闭动画对齐 HyperOS 3。
- **M5**：signature Binder、crash 计数、救援开关、release。

## 许可证

**AGPL-3.0**。见 [LICENSE](LICENSE) 与 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

致敬并参考：Free、YAMF / YAMF²、LMOFreeform、Mi-FreeForm、NeXtep、MaxFreeForm、HyperCeiler、OneStep4。它们分别解决了扇形手势、VirtualDisplay、AOSP freeform、侧栏、SystemUI 工程化、HyperOS 入口、单应用规则和工作台形态的问题；本项目只把可移植的部分拆开重组，不直接搬运 OEM 私有代码。
