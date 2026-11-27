# 迭代日志

## Cycle 0 · 0.5.0 基线（2026-11-27）

**目标**：冻结功能扩张，先把 0.5.0 的原生 caption、日志、深色模式在真机上跑通。

### 本轮完成

- 0.5.0 源码与 APK：原生 `CaptionWindowDecoration` 胶囊、日志分享、日/夜间主题。
- 市场研究：Flyme / HyperOS / ColorOS / OriginOS / One UI 的小窗交互拆解。
- 产品计划：P0–P6 路线、WindowSession 架构、每轮冒烟与总结规则。
- 主机静态冒烟：26/26 通过。

### 本轮没做

- 没有新增贴边、侧边栏、双击尺寸循环、多窗。原因：P0 真机未通过，先不加变量。
- 没有真机功能执行：当前环境无 adb / emulator。

### 阻塞

- 需要 PixelOS 17 / LOS 17 真机的安装、菜单、freeform、胶囊、拖动、迷你、关闭结果与日志。

### 下一轮入口

1. P0 真机 18 条用例通过，或拿到失败日志定位。
2. 通过后写 `WindowSession` 状态机设计 + 纯 Java 单测，再改 SystemUI。
3. 每轮只做一个用户可感知的功能，做完更新本日志。

### 决策记录

- 继续使用原生 caption，不回退到 App 悬浮窗 chrome。
- 任何新功能必须有开关；Hook 失败 fail-open。
- 没有真机结果前，不把静态冒烟写成“功能通过”。

## Cycle 1 · 1.0.0-alpha1 米窗路线（2026-11-27）

**目标**：停止在 freeform + 自绘壳上打补丁，改成 YAMF 的虚拟屏 + 悬浮卡片，让用户第一次看到“真实应用内容在可拖动卡片里”。

### 本轮完成

- 新增 `VirtualWindowEngine`：AMS `systemReady` 后初始化，接收 `OPEN_VIRTUAL_WINDOW`，主线程创建窗口。
- 新增 `VirtualWindow`：`DisplayManager` 创建 trusted 虚拟屏，`TextureView` 镜像，标题栏拖动，右下角缩放，底栏返回/桌面/全屏/关闭，任务消失看门狗。
- 新增 `VirtualWindowInput`：触摸/按键按 `displayId` 注入。
- 新增 `VirtualWindowClient`：App 侧广播入口。
- `AppPickerActivity` 改走虚拟屏；移除主界面的“显示小窗预览 / 侧边栏 / 底角扇形 / 停止预览”，避免再把 DemoOverlay 当产品。
- `LauncherHook` 按 YAMF 修：`taskView.getTask().key.id` 取 taskId，`shortcuts[0].mItemInfo` 取 ItemInfo，`RemoteActionShortcut` 支持 3/4 参数构造，PendingIntent 直接发给 `android`。
- 文档：`docs/VIRTUAL_WINDOW.md`。

### 未验证 / 风险

- 没有 API 37 + LSPosed 环境，未做真机冒烟；以下都是静态实现：广播到 `android` 是否可达、system_server 能否添加 `TYPE_APPLICATION_OVERLAY`、trusted 虚拟屏是否被 PixelOS 放行、输入注入是否被 InputDispatcher 接受。
- 未做 IME、旋转、多窗层叠、贴边、迷你动画。
- 0.9.x 的 freeform 代码保留但不再是 AppPicker 主路径；SystemUI overlay 仍可能被自检/旧入口触发。

### 下一轮入口

1. 拿到 alpha1 的 LSPosed 日志：`NativeWindow/VWE: ready`、`NativeWindow/VW: virtualDisplay=…`、`surface … -> display …`。
2. 若广播未到：改 binder/ContentProvider 桥；若 overlay 被拒：换 `mUiContext` 或改 SystemUI 进程承载 overlay。
3. 通过后补 IME（display IME policy local + 临时 focusable）、旋转（watchRotation）、多窗层叠。

## Cycle 2 · 市面方案整合与核心策略（2026-11-27）

**目标**：把 YAMF、Mi-FreeForm、MiFreeFormX、MaxFreeForm、Free 等方案拆成公共核心，先确定哪些可复用、哪些是 ROM 私有坑，避免继续把功能堆在单一 freeform 链路上。

### 本轮完成

- 新增 `docs/MARKET_INTEGRATION.md`：参考项目矩阵、双后端目标架构、启动/退出裁决、几何规则、版本路线与禁区。
- 克隆并核对：Mi-FreeForm、MiFreeFormX、MaxFreeForm；本地已有 YAMF、Free。
- 抽出纯 Java 策略层 `core/`：
  - `TargetWindowForm`：None / VisibleWindow / FlexibleHidden / BackgroundTask。
  - `FanLaunchAction`：FrontExisting / ToggleFlexible / LaunchAsZoom。
  - `LaunchPolicy`：可见前置、隐藏恢复、后台/无任务新开；AOSP 无可靠 toggle 时回退启动路径。
  - `ClosePolicy`：仅系统裁决的浮标退出才替换为退后台；手动拖边缘不干预。
  - `GeometryPolicy`：尺寸夹取、安全区、左右贴边阈值、把手宽度。
- 静态冒烟从 38 项扩到 44 项，全部通过。

### 结论

- AOSP 17 主线先保留虚拟屏后端；原生 freeform 作为第二后端，等目标 ROM 真机验证后再切主。
- MIUI/ColorOS 私有类只进入可选 VendorBackend，默认不加载、不复制素材。
- 下一轮先补 1.1：逻辑分辨率/视图 scale 解耦、旋转、IME、已有任务去重、位置持久化；在没有真机结果前不加侧边栏/扇形/多窗。
