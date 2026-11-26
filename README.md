# native-window

> 类原生 Android 17 的 HyperOS 3 / Flyme 风格小窗。  
> 目标设备：**PixelOS 17 / LineageOS 17**（Android 17，API 37）。  
> 形态：**LSPosed + Root**，AGPL-3.0。

## 这是什么

一个把「国产 UI 的小窗体验」拆成可组合层的实验项目：

- **入口层**：启动器长按、最近任务菜单、底角扇形、侧边栏。
- **动画层**：自研物理弹簧，不用 `ViewPropertyAnimator` 的默认曲线。
- **窗口层**：AOSP `WINDOWING_MODE_FREEFORM`；迷你/贴边用 VirtualDisplay 回退。
- **适配层**：LOS `com.android.launcher3` 与 Pixel Launcher `com.google.android.apps.nexuslauncher` 双入口，类不存在时 fail-open。

当前不是成品。`0.2.0-alpha` 先交付**可安装的视觉基线 + 动画/最近任务/桌面入口骨架**；系统级窗口控制器、WCT 拖动缩放、SystemUI chrome、VirtualDisplay 迷你/贴边仍在推进。

## 当前进度

| 模块 | 状态 | 说明 |
|---|---|---|
| HyperOS 3 风格小窗预览 | ✅ 可安装 | 圆角卡片、顶部胶囊、底部把手、阴影、迷你 chip |
| Flyme 皮肤 | ✅ 可切换 | 浅色、大圆角、不同胶囊 |
| 自研弹簧动画 | ✅ | `anim/SpringAnimator.java`：半隐式欧拉 + 子步进 |
| 底角扇形 | ✅ 预览 | 四分之一圆触发、7 项、左右分组、松手选择 |
| 侧边栏 | ✅ 预览 | 边缘把手、10 项、弹簧展开 |
| 边缘吸附 / 最小化 | ✅ 预览 | 拖到左右边缘 → 迷你 chip；点 chip 恢复 |
| 最近任务菜单注入 | 🟡 骨架 | Hook `TaskOverlayFactory#getEnabledShortcuts`，加“小窗”动作 |
| Pixel / LOS 启动器适配 | 🟡 骨架 | 按包名 + 类名双适配；Pixel 混淆时 fail-open |
| system_server freeform 门禁 | 🟡 骨架 | `ActivityTaskManagerService` 字段 patch |
| 真实 App 入窗 | ⏳ | 需要接 SystemUI/WCT 或 VirtualDisplay 内核 |
| WCT 逐帧拖动缩放 | ⏳ | 下一阶段 |
| 多窗 / 分屏 / 桌面模式 | ❌ | 首版不做 |

## 为什么自研动画

系统默认的 `ViewPropertyAnimator` / `Interpolator` 在窗口缩放、贴边、扇形展开时会出现「轻、飘、断」：

- 窗口开启要有重量感：轻微过冲后收住，不能线性放大。
- 迷你/贴边要快、硬、可中断，不能排队。
- 扇形要从触发点长出来，图标有先后，不是整体淡入。

`SpringAnimator` 用固定子步进（4 步）跑物理弹簧，60/90/120/144Hz 下结果一致；每个属性独立弹簧，可中途反向。参数：

```java
SpringAnimator.windowOpen(p); // k=420, c=34
SpringAnimator.snap(p);       // k=560, c=38
SpringAnimator.radial(p);     // k=360, c=28
```

## 最近任务与启动器

### LOS / Launcher3

`com.android.quickstep.TaskOverlayFactory#getEnabledShortcuts` 返回 `List<SystemShortcut>`，在 after Hook 里追加一个 `RemoteActionShortcut`：

- 标题：**小窗**
- 动作：`PendingIntent` → `OpenReceiver`
- 携带：`taskId`
- 点击：`SmallWindowBridge.openFromTask()` 走 `ActivityTaskManager.startActivityFromRecents(taskId, freeformOptions)`

### PixelOS / Pixel Launcher

Pixel Launcher 基于 Launcher3，但类名可能被混淆、随 Play 更新。适配策略：

1. 先按 `com.android.quickstep.TaskOverlayFactory` 查找；
2. 找不到就保留系统自带最近任务入口；
3. 不 crash、不 bootloop，只记 LSPosed 日志。

这不是「硬改 Pixel Launcher」，而是**在它自己的菜单体系里加一项**；类名变了就 fail-open。

## 目录

```text
native-window/
├── app/src/main/
│   ├── java/io/github/wumisc/nativewindow/
│   │   ├── MainActivity.java          # 设置 / 预览入口
│   │   ├── OverlayService.java        # 悬浮窗服务
│   │   ├── DemoOverlay.java           # HyperOS/Flyme chrome + 扇形 + 侧栏
│   │   ├── anim/SpringAnimator.java   # 自研物理弹簧
│   │   ├── core/                      # WindowForm / SmallWindowRequest
│   │   └── hook/
│   │       ├── ModuleEntry.java       # LSPosed 入口（根目录）
│   │       ├── SystemServerHook.java  # freeform 门禁
│   │       ├── LauncherHook.java      # 最近任务菜单注入
│   │       ├── SmallWindowBridge.java # startActivityFromRecents 封装
│   │       └── OpenReceiver.java      # 菜单动作落点
│   ├── res/                           # 主题、图标、字符串
│   └── assets/xposed_init
├── libs/api-82.jar                    # 编译用 Xposed API（不打包进 APK）
├── scripts/build.sh                   # 无 Gradle 的本地构建
└── dist/                              # 构建产物（.gitignore）
```

## 构建

### Android Studio

1. 用 Android Studio 打开根目录；
2. JDK 17+，Android SDK Platform 37；
3. Sync 后运行 `app`。

Gradle 使用 AGP `9.5.3`，`compileSdk 37`、`minSdk 34`、`targetSdk 37`。

### 命令行（不需要 Gradle）

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export JAVA_HOME=/path/to/jdk-17
bash scripts/build.sh
# 输出：dist/native-window-0.2.0-alpha.apk
```

脚本会依次执行 `aapt2 compile/link → javac → d8 → zipalign → apksigner`。

## 安装

1. 安装 APK；
2. 授予「显示在其他应用上层」；
3. 打开 App，点“显示小窗预览 / 侧边栏 / 底角扇形”；
4. LSPosed 中启用模块，作用域：
   - **系统框架**（`android`）：freeform 门禁；
   - **Launcher / Quickstep**（`com.android.launcher3` 或 `com.google.android.apps.nexuslauncher`）：最近任务菜单；
5. 重启 SystemUI / 设备。

> 当前 `OpenReceiver` 为了跑通链路是 exported；正式版会换成 signature Binder + UID 校验，见“安全”。

## 适配边界

- **PixelOS 17**：Pixel Launcher 可能混淆；只做菜单注入，不替换桌面。
- **LOS 17**：Trebuchet / Launcher3 开源，优先跑通。
- 不用 ColorOS `OplusZoomWindowManager`、`FlexibleTaskController`、`getFlexibleZoomState`；这些在 AOSP 不存在。
- 不复制小米/魅族/OPPO 的商标、系统图标、字体或私有素材；图标来自已安装应用。
- 首版不做多窗、分屏、桌面模式、折叠屏/平板。

## Roadmap

- **M1**：真实 App 入窗（SystemUI/WCT 或 VirtualDisplay 单槽）。
- **M2**：WCT 逐帧拖动/缩放、旋转、关闭动画。
- **M3**：迷你/贴边真实任务迁移，贴边保活。
- **M4**：Pixel Launcher DexKit 指纹、LOS 图标长按菜单。
- **M5**：signature Binder、crash 计数、救援开关、release。

## 安全

- 当前 `OpenReceiver` 是 alpha 链路，正式版必须换成 **signature 权限 Binder**，校验调用方 UID/签名。
- `SystemServerHook` 只 patch 三个布尔字段，失败只写日志。
- 每个 Hook 独立 try/catch；Launcher 类不存在时 fail-open。
- 发布仓库只放源码，不放 APK；构建产物只进 `dist/` 且被 `.gitignore`。

## 许可证

**AGPL-3.0**。见 [LICENSE](LICENSE) 与 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

致敬并参考：Free、YAMF / YAMF²、LMOFreeform、Mi-FreeForm、NeXtep、MaxFreeForm、HyperCeiler、OneStep4。它们分别解决了扇形手势、VirtualDisplay、AOSP freeform、侧栏、SystemUI 工程化、HyperOS 入口、单应用规则和工作台形态的问题；本项目只把可移植的部分拆开重组，不直接搬运 OEM 私有代码。
