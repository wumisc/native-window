# 0.9.0-aosp-first 自动自检说明

## 这版为什么改

你给的日志已经说明问题不在“再点一次”：

```text
device=Xiaomi M332BF
release=17 sdk=37
build=CP2A.260605.016
SelfTestActivity winConfig mWindowingMode=fullscreen
getMiuiFreeformStackInfo = null
root=false
```

`0.6.0` 走的是 **PixelOS / LineageOS 的 AOSP WMShell** 路径：
`ShellTaskOrganizer + WindowContainerTransaction + CaptionWindowDecoration`。
HyperOS 4 的 freeform 是 MIUI 自研栈：`MiuiFreeFormManagerService`、
`MiuiFreeFormActivityStack`、`MiuiBaseWindowDecoration`，桌面是 `com.miui.home`。
所以在 HyperOS 4 上，`0.6.0` 的 SystemUI 类找不到，任务当然一直 fullscreen。

`0.6.1` 不再假装 AOSP 路径能用，改成双分支：

| 环境 | 自检方式 |
|---|---|
| HyperOS 4 | 用 MIUI 的 `ActivityOptions`：`FREEFORM 5` + `setMiuiConfigFlag(2)` + `MiuiMultiWindowUtils.getFreeformRect` 拉起独立 `ProbeActivity`，由探针测量自己是不是小窗 |
| PixelOS / LOS 17 | 保留原来的 SystemUI WCT 往返 |

## 推荐应用为什么之前没有

LSPosed 的“推荐应用”不是自动猜的，它读模块 `AndroidManifest.xml` 里的：

```xml
<meta-data
    android:name="xposedscope"
    android:resource="@array/xposed_scope" />
```

0.6.1 只声明了 `xposedmodule`，没有 `xposedscope`，所以列表只剩“所有应用”，没有“推荐应用”。
0.6.2 补上了 `res/values/arrays.xml` 的 `xposed_scope`：`android`、`com.android.systemui`、
`com.android.launcher3`、`com.google.android.apps.nexuslauncher`、`com.miui.home`。

## 怎么用

1. 安装 `native-window-0.9.0-aosp-first.apk`。
2. LSPosed 启用模块，作用域至少勾：**系统框架（system）**、**SystemUI（com.android.systemui）**；HyperOS 桌面是 `com.miui.home`，要桌面入口时再勾。
3. 重启 SystemUI / 重启手机。
4. 打开 App，**不用点任何按钮**。

## 结果怎么看

- **ROM = HyperOS 4**：走 MIUI 探针。
- **HyperOS 小窗启动 = ✓**：`ActivityOptions` 已被接受。
- **探针结果 = ✓**：独立 `ProbeActivity` 的窗口尺寸真的比屏幕小，说明 HyperOS 4 的 freeform 已生效。
- **探针结果 = ✕**：探针仍是全屏，或 `startActivity` 抛异常。先确认系统框架作用域；再看 LSPosed 日志里有没有
  `NativeWindow/HyperOS-SS: HyperOS freeform gates installed`。
- **AOSP 分支**（PixelOS / LOS）仍显示原来的“任务尺寸”行；通过标准不变。

## 这一版还没做的

- **HyperOS 4 的顶栏胶囊**：`HyperOsSystemUiHook` 已挂 `MiuiBaseWindowDecoration#createTopCaption`，但不同 HyperOS 4 版本的顶栏 View 结构不同；胶囊能不能挂上要看日志
  `NativeWindow/HyperOS-Caption: capsule attached`。挂不上不会崩 SystemUI，会跳过。
- **HyperOS 4 桌面快捷菜单**：`com.miui.home` 是小米自己的桌面，`0.6.1` 还没注入“小窗”菜单；先用系统已有入口/自检探针验证 freeform。
- **侧边栏、贴边、迷你、双窗**：仍未实现。
- **root 日志**：日志里 `root=false`，`dumpsys` 被拒；要完整任务栈，需要在 root 管理器里给模块 App 授权 `su`。

## 构建与校验

```bash
ANDROID_HOME=/home/user/tools/android-sdk JAVA_HOME=/home/user/tools/jdk bash scripts/build.sh
ANDROID_HOME=/home/user/tools/android-sdk bash scripts/smoke.sh
```

当前静态冒烟：33/33。

## 版本

- versionCode 9
- versionName 0.9.0-aosp-first
- 包名 io.github.wumisc.nativewindow
- APK：`dist/native-window-0.9.0-aosp-first.apk`
- sha256：`ca059b7bfba8d24b8086be29bff9d1de7131959d8bf6ec0dd662a73f23cecbe7`
