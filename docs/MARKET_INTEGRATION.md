# 类原生小窗：市面方案整合路线

> 目标：不做某一套 ROM 私有窗口的复刻，而是把市面验证过的启动策略、窗口状态、几何动画、入口和输入处理抽成自己的公共核心；底层按 AOSP 17 / PixelOS / LOS 17 选择最稳后端，后续再挂厂商适配。

## 一、先分清两种窗口路线

市面小窗不是同一种技术，整合前必须先拆开：

| 路线 | 代表 | 窗口是什么 | 优点 | 代价 / 风险 |
|---|---|---|---|---|
| 虚拟屏 + 悬浮镜像 | YAMF、Mi-FreeForm | system_server 创建 trusted `VirtualDisplay`，Activity 跑在虚拟屏，`TextureView` 把画面镜像到 overlay；触摸按 `displayId` 注入 | 不依赖 ROM freeform caption；窗口标题栏/拖动/缩放自己控制；类原生上可控性高 | 需要系统权限创建虚拟屏；输入注入、IME、旋转、多窗焦点要自己补；逻辑分辨率和视图尺寸要做缩放解耦 |
| 原生 freeform Task | AOSP WMS / Taskbar、Free(ColorOS)、MaxFreeForm/MiFreeFormX(MIUI) | 真实 Task 被系统改成 freeform 或厂商 flexible window | 系统负责输入、焦点、生命周期、转场；跨窗交互更自然 | 类原生手机常缺 caption；ROM 私有方法随版本变；非可缩放应用、数量上限、黑名单要逐 Hook 解锁 |
| Shizuku/Shell 任务控制 | XGesture 一类 | 提权后通过 task/window 服务移动、启动、冻结 | 不需要常驻系统 Hook 也能调任务 | 没有自绘窗口壳，体验依赖系统 freeform；提权链路和权限要单独维护 |

**结论**：native-window 不能继续只押 WCT freeform 一条路。公共核心必须先和后端解耦；AOSP 17 上先走虚拟屏闭环，等原生 freeform 在目标 ROM 验证稳定后，再作为第二后端复用系统能力。

## 二、参考项目拆解

### 1. YAMF — Yet Another Mi-FreeForm

- 源码：`reference/YAMF`，GPLv3，Kotlin，Android 12–13 基线。
- 机制：`DisplayManager.createVirtualDisplay(...)` + `TextureView`/`SurfaceView` + `TYPE_APPLICATION_OVERLAY`；`TaskStackListener` 更新图标标题；`InputManager.injectInputEvent` 注入触摸；返回/桌面/全屏按键按 `displayId` 注入；全屏把 root task 移回 display 0。
- 入口：最近任务、Taskbar、桌面 popup；应用列表；配置持久化；广播控制。
- 可直接吸收：
  - 虚拟屏创建、Surface 绑定、`resize` 时机。
  - 最近任务 `taskView.getTask().key.id` 取 taskId；`RemoteActionShortcut` 3/4 参构造兼容。
  - `ActivityOptions.setLaunchDisplayId` 启动到虚拟屏；`moveRootTaskToDisplay` 恢复全屏。
  - `RotationWatcher` 思路、IME 策略开关、应用列表入口。
- 不要照搬：
  - 它的 Android 12/13 隐藏 API 签名不能假设 API37 兼容。
  - Kotlin/EzXHelper 依赖；我们当前手工 Java 构建链先保留 Java 核心。
  - 配置格式、视觉素材和图标。

### 2. Mi-FreeForm 3

- 源码：`reference/Mi-FreeForm`，GPLv3，Kotlin；App 侧通过 Shizuku/Sui 与 privileged freeform-server 交接。
- 机制：同样是虚拟屏镜像；`FreeformConfig` 区分**逻辑分辨率**（`width/height/densityDpi`）和**视图尺寸**（`freeformWidth/freeformHeight`），`scale = min(屏短边/逻辑短边, 屏长边/逻辑长边)`；触摸坐标乘 scale。
- 可直接吸收：
  - 逻辑分辨率与 overlay 视图解耦，避免每次缩放都改应用配置。
  - `FreeformAnimation`：贴边回屏、全屏动画用 `ValueAnimator` 逐帧更新 WindowManager layout params。
  - `RotationWatcher`：默认屏旋转后把窗口拉回屏幕内。
  - 通知监听、快捷设置 Tile、侧边栏服务、PendingIntent 开窗、应用列表这些入口形态。
  - 挂起前位置/尺寸记录，恢复时回退原 bounds。
- 风险：freeform-server/binder 交接较重；AOSP 上先由 system_server 引擎承载，不先复刻 Shizuku 服务。

### 3. MiFreeFormX

- 源码：`reference/MiFreeFormX`，MIT；MIUI 14 / Android 12–13。
- 功能面：通知点击开小窗、控制中心快捷方式、应用间跳转/分享、平行小窗、黑名单、位置偏移修正、数量限制、失焦行为（挂起/贴边/关闭）。
- 可吸收：
  - 失焦行为必须是可配置策略，而不是硬编码关闭。
  - 应用间跳转/分享也走统一开窗策略。
  - 平行小窗作为后续多窗能力，不阻塞单窗闭环。
- 不要照搬：`MiuiFreeFormActivityStack`、`MiuiFreeFormGestureController`、`MiuiFreeFormManagerService` 等 MIUI 私有类。

### 4. MaxFreeForm

- 源码：`reference/MaxFreeForm`，GPLv3，Kotlin + libxposed API102，Miuix 设置。
- 机制：在 system_server / Home / SystemUI 分进程 Hook，解锁多窗、非可缩放应用、数量上限、贴边、前台挂起；桌面快捷菜单补小窗/多开；SystemUI 移除/隐藏小窗顶底栏。
- 可吸收：
  - 通用 AOSP 解锁点：`supportsSplitScreen=true`、`mDevEnableNonResizableMultiWindow=true`、`Task.isResizeable=true`、freeform 相关门禁放开。
  - Hook 分进程扫描注册、feature flag、热重载结构（后续我们的设置层可参考 Miuix 但不引它）。
  - 桌面入口补“小窗/多任务”动作。
- 不要照搬：MIUI 黑名单、`MiuiFreeFormStackDisplayStrategy`、游戏窗互斥调用栈判断。

### 5. Free — Flyme 小窗（ColorOS 15+/16/17）

- 源码：`reference/Free`，GPLv3，Kotlin，libxposed API102。
- 机制：它在 ColorOS 上让系统原生 flexible window 工作，模块**不创建、不移动、不缩放窗口**，只做手势、策略、面板和退出裁决。
- 最值得吸收的是纯策略层：
  - `WindowForm`：`None / VisibleWindow / FlexibleHidden / BackgroundTask`。
  - `FanLaunchAction`：`FrontExisting / ToggleFlexible / LaunchAsZoom`。
  - `FanLaunchPolicy`：已有可见窗口只前置不新建；不可见浮动窗口恢复；后台任务才新开。
  - `ClosePolicy`：只有系统裁决的浮标退出才替换成退后台；用户手动拖边缘不干预。
  - 窗外点击、IME 避让边界、面板入场/退场动画、失效降级路径。
- 不要照搬：`OplusFlexibleAnimationRunner`、侧边栏私有交易码、ColorOS `TaskExtImpl`。

### 6. NeXtep / XGesture（外部参考）

- NeXtep：ColorOS 16/17 侧工作区，主窗 + 三个任务槽，任务切换、拖拽、工作区监控；可吸收“多任务槽、切换动画、导航栏避让”。
- XGesture：Shizuku 提权下的任务/窗口/手势工具；可吸收“当前应用转小窗、后台任务管理、手势检测”的产品形态，不复制其 Shizuku 命令集。

## 三、目标公共架构

```text
                         ┌──────────────────────────────┐
                         │  core/ policy · state · geometry │
                         │  LaunchPolicy / ClosePolicy     │
                         │  WindowSession / Geometry       │
                         └──────────────┬───────────────┘
                                        │ 统一开窗/状态命令
          ┌─────────────────────────────┼─────────────────────────────┐
          ▼                             ▼                             ▼
 VirtualDisplayBackend           AospFreeformBackend           VendorBackend
 (YAMF/Mi-FreeForm 路线)          (WMS/Taskbar freeform)          (ColorOS/MIUI 适配层)
          │                             │                             │
          └──────────────┬──────────────┴──────────────┬──────────────┘
                         ▼                               ▼
              overlay chrome / panel / animation      platform reflection adapter
                         ▲                               ▲
          AppPicker · Recents · Sidebar · Radial · Notification · Tile
```

### 分层职责

1. **core（纯逻辑，不碰隐藏 API）**
   - `WindowForm` / `WindowAction` / `CloseDecision`。
   - `WindowSession`：component、taskId、displayId、state、normal/mini/edge bounds、position、lastActive、source。
   - `GeometryPolicy`：最小/最大尺寸、安全区、贴边阈值、横屏尺寸、位置持久化键。
   - 所有决策可被 JVM 单测覆盖。

2. **bridge（跨进程）**
   - 统一命令：open component/task、front existing、restore hidden、close、toFullscreen、toMini、snapEdge、closeAll。
   - 当前先走广播；稳定性验证后再评估 binder/content provider。
   - 来源校验：module/launcher/system，记录 source 和 uid。

3. **backend（窗口承载）**
   - `VirtualDisplayBackend`：虚拟屏、Surface、输入注入、旋转、全屏回屏。
   - `AospFreeformBackend`：保留现有 WCT/freeform 代码，作为目标 ROM 原生 freeform 可用时的备选。
   - `VendorBackend`：只放可开关的 ROM 适配，默认关闭；类原生主线不依赖它。

4. **entries（入口）**
   - AppPicker：模块内选择应用。
   - Recents：最近任务卡片菜单。
   - Sidebar：边缘应用栏。
   - Radial：底角扇形菜单。
   - Notification / Tile / Control Center：通知与快捷入口。

5. **platform（反射适配）**
   - 类名/方法签名按设备 API、ROM 加载后核对；找不到就降级，不崩系统。
   - 每类 Hook 独立 try/catch，日志码可搜索。

## 四、必须先补齐的核心规则

### 启动裁决（吸收 Free + MiFreeFormX）

| 目标状态 | 动作 |
|---|---|
| 已有可见小窗/全屏任务 | 前置已有任务，不新开重复实例 |
| 已有隐藏浮动窗（迷你/贴边） | 恢复原窗口，不重新启动 |
| 后台普通任务 | 按小窗启动；必要时移动现有 task 到虚拟屏 |
| 无任务 | 新建 Activity 到虚拟屏 |
| 黑名单/禁用应用 | 直接拒绝并记录日志 |

### 几何与输入（吸收 Mi-FreeForm + YAMF）

- 虚拟屏逻辑分辨率固定一份；overlay 视图按 `scale` 缩放，触摸坐标反算回逻辑分辨率。
- 窗口记住每个应用上次位置、尺寸；下次开窗复用，越界自动拉回安全区。
- 旋转监听：横竖屏交换宽高，不拉伸画面。
- 输入注入：单/多指触摸、返回/桌面/最近任务键；IME 用本地 display policy 单独处理。
- 焦点/z-order：点击窗口置顶；多窗关闭一个不影响其他；隐藏窗口不抢焦点。

### 状态机

```text
NONE ──open──▶ OPENING ──▶ NORMAL ──┬──▶ MINI
                                   ├──▶ EDGE_LEFT / EDGE_RIGHT
                                   ├──▶ FULLSCREEN（回主显示，不删任务）
                                   └──▶ CLOSING ─▶ NONE
```

- `NORMAL`：完整标题栏、底栏、可拖动/缩放。
- `MINI`：缩小卡片，单击恢复，双击全屏。
- `EDGE_*`：贴边半隐藏，保留把手；恢复时回到贴边前 bounds。
- `FULLSCREEN`：任务移回默认显示，释放虚拟屏/overlay。
- `CLOSING`：释放虚拟屏、移除 View、注销监听、回收 Surface。

## 五、版本路线

### 1.0 alpha — 单窗虚拟屏闭环（当前）

- 一个虚拟屏、一个悬浮卡片、真实应用内容。
- 标题栏拖动、右下角缩放、返回/桌面/全屏/关闭、图标标题。
- 验收：AppPicker 打开设置；卡片可见；触摸能滚动；拖动/缩放/全屏/关闭不崩。

### 1.1 核心策略与几何修复

- 逻辑分辨率/视图 scale 解耦，修正触摸坐标。
- 旋转 watcher、横屏交换宽高。
- IME 本地策略 + 可切换 focusable。
- 已有任务去重、前置/恢复策略接入 core。
- 位置/尺寸持久化；窗口不越界。
- 日志码覆盖：backend open/surface/input/close/fullscreen。

### 1.2 状态与贴边

- Mini 尺寸、单击/双击语义。
- 左右边缘吸附与把手；贴边前后位置恢复。
- 窗外单击/双击关闭（可开关）。
- 开窗/关窗/全屏/贴边动画；跟随系统动画缩放。
- 多窗注册表、置顶、最大数量与最旧回收策略。

### 1.3 入口矩阵

- 最近任务菜单已起步，先在 Pixel Launcher / LOS Launcher3 双路验证。
- 侧边栏应用列表：搜索、排序、最近小窗、工具入口。
- 底角扇形：最多七格，拖到目标松手；更多面板走内建网格兜底。
- 通知小窗、快捷设置 Tile、控制中心快捷方式。

### 1.4 应用间能力

- 跳转/分享优先小窗；保留授权、登录、通话类全屏规则。
- 平行小窗/双窗；最小化窗口聚合。
- 分应用规则：禁用小窗、强制横屏、游戏模式、失焦行为。
- 通知气泡/待办窗口作为可选增强。

### 1.5 双后端与厂商适配

- AOSP 原生 freeform 可用时优先系统 Task；caption 缺失或异常时回退虚拟屏。
- ColorOS/MIUI 适配层独立开关；类原生默认不加载私有 Hook。
- 兼容矩阵：Android 17、API37、PixelOS 17、LOS 24/17；记录真机结果。

## 六、不能踩的坑

- 不要把 DemoOverlay 当产品；预览壳只能留在开发诊断。
- 不要在没有真机签名/方法核对前硬写厂商私有类名。
- 不要一次性做多窗/贴边/手势；先让单窗输入、生命周期、关闭可靠。
- 不要把厂商图标、布局、资源拷进仓库；交互可以借鉴，素材自己做。
- 静态编译通过不等于功能通过；每条入口和状态都要有真机日志背书。
