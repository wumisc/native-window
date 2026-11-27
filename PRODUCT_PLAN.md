# Native Window 产品计划

> 目标：在 **PixelOS 17 / LineageOS 17（Android 17）** 上，用 **LSPosed + Root** 做一个可长期维护的开源小窗增强层。
> **2026-11-27 修订**：主线改为“公共核心 + 双后端”，市面方案拆解见 [`docs/MARKET_INTEGRATION.md`](docs/MARKET_INTEGRATION.md)；当前先以虚拟屏后端闭环，原生 freeform 作为第二后端。
> 不是复刻 Flyme / HyperOS，而是把市面上验证过的交互拆开，做成“可配置、可回退”的自己的产品。
> 当前基线：`1.0.0-alpha3`（虚拟屏悬浮卡片；IME/旋转/多窗置顶/贴边迷你已接骨架；PixelOS/LOS ROM 画像与入口适配已落地，待真机验收）。

## 一、产品定位

**一句话：让类原生 Android 拥有国产 UI 级别的小窗，但不把系统改成国产 UI。**

三条硬原则：

1. **原生 Surface 优先**：窗口是 AOSP freeform Task，chrome 在 `CaptionWindowDecoration` 里，不再用 `TYPE_APPLICATION_OVERLAY` 贴一层假 chrome。
2. **可回退、可关闭**：每个新功能都有开关；Hook 失败要 fail-open，不能让 SystemUI 起不来。
3. **不抄 OEM 私有实现/素材**：只借鉴公开交互和系统 API；不复制 ColorOS `OplusZoomWindowManager`、Flyme 私有类、OEM 图标字体。

## 二、市场研究：哪些优点值得拿

### Flyme（重点）

Flyme 的小窗不是“一个悬浮窗”，而是“呼出—轻量打开—手势—贴边”的完整链路：

- **主动呼出**：屏幕左下角/右下角向内滑，呼出小窗菜单；菜单可增删应用、拖动排序。
- **轻量打开**：通知、设置跳转、分享、文本操作、识屏等场景，默认用小窗打开，减少“跳出当前应用”的割裂感。
- **小窗手势**：
  - 按住底部指示条向外拖 → 全屏；
  - 向内拖 → 缩小挂起；
  - 轻扫悬浮小窗 → 移动位置；
  - 双指展开/捏合 → 缩放；
  - 轻点悬浮小窗 → 回到小窗；
  - 拖到左右边缘 → 贴边收起，点指示条恢复；
  - 点空白处关闭，双击空白关闭可开关。
- **Flyme 10**：小窗一步贴边；悬浮小窗双指缩放；贴边后点把手/向内滑恢复。
- **Flyme AIOS 5.0（发布会信息，待真机验证）**：自由小窗 + 迷你小窗交换；点顶栏呼出最大化/关闭菜单；双小窗切换；更轻量的贴边隐藏。

值得拿：**底角呼出、轻量打开、底部指示条语义、贴边把手、双指缩放、双击空白关闭（可选）**。

### HyperOS

- 侧边栏：滑动呼出，点/拖应用开小窗；可拖到屏幕中间开小窗、拖到顶部全屏。
- 最近任务：任务卡菜单直接“小窗”。
- 原生胶囊：顶部胶囊 + 最小化/全屏/关闭；最小化成边缘气泡，点回恢复。
- 多窗：支持多个浮窗、堆叠、快速切换；小窗顶部菜单可切全屏/分屏/关闭。

值得拿：**胶囊按钮的可见性、最近任务入口、侧边栏拖拽语义、迷你气泡**。

### ColorOS

- 自由浮窗：上滑停顿呼出；顶部菜单切全屏/关闭；拖角缩放；缩到最小变迷你浮窗。
- 迷你浮窗：单击放大，双击全屏。
- 双浮窗：最多两个并行，更多窗口收进侧边浮标；侧边栏保留最近两个浮窗。
- 简洁模式：隐藏全屏/关闭按钮，双击顶部把手关闭。
- 支持把文件从浮窗拖到另一个全屏应用。

值得拿：**迷你单击/双击语义、简洁模式、双浮窗上限、文件拖拽**。

### OriginOS

- 迷你小窗：单击放大，双击全屏。
- 侧边挂起：拖到左右边缘快速挂起。
- 拖到底部删除区关闭。
- 放大到极限继续拖 → 全屏；拖到上下边缘停顿 → 分屏。
- 小窗四边可缩放（不只两个角）。

值得拿：**边缘挂起、底部删除区、四边缩放、明确的按钮降低学习成本**。

### One UI

- 侧屏面板：滑动呼出，拖到中间开弹出视图，拖到上下开分屏。
- 弹出视图：拖到屏幕边缘贴靠；点恢复；拖角最小化/最大化。
- App pairs：分屏组合保存，下次一键打开。
- 多个最小化窗口合并成一个图标，点开预览选择。

值得拿：**App pairs、最小化窗口聚合、边缘贴靠**。

### 取舍表

| 能力 | 用户价值 | 实现成本 | 首版 |
|---|---|---|---|
| 原生 caption 胶囊 | 不两张皮、圆角阴影原生 | 已完成 | ✅ |
| 最近任务入口 | 最低学习成本 | 中 | ✅ 已有 |
| 双击胶囊/迷你切换 | 符合 Flyme/ColorOS 直觉 | 低 | P1 |
| 贴边挂起 + 边缘把手 | 小窗不挡内容 | 高 | P2 |
| 侧边栏真实开窗 | 主动呼出 | 高 | P3 |
| 轻量打开（通知/分享/设置） | 减少跳出 | 高 | P3 |
| 多窗 / 双小窗 | 多任务 | 很高 | P5 |
| App pairs | 高频组合 | 高 | P5 |
| 文件拖拽 | 跨应用效率 | 很高 | 以后 |

## 三、产品功能集（分版本）

### P0：真机基线（必须先过）

**目标**：证明 0.5.0 在 PixelOS 17 / LOS 17 上不是“看起来能装”，而是真能开、能拖、能关。

范围：安装、深色、悬浮窗权限、日志分享、最近任务菜单、freeform 开窗、原生胶囊、拖动、缩放、迷你、全屏、关闭。

验收：`SMOKE_TEST.md` 的 18 条 P0/P1 用例；任何 SystemUI crash / ANR 一票否决。

**规则：P0 不过，不写新功能。**

### P1：WindowSession + 尺寸循环

**目标**：把小窗从“一次性的 bounds 修改”变成有状态的会话。

- `WindowSession`：`taskId`、`packageName`、`state`、`normalBounds`、`miniBounds`、`edgeSide`、`lastActive`。
- 状态：`NORMAL` / `MINI` / `EDGE_LEFT` / `EDGE_RIGHT` / `FULLSCREEN` / `DISMISSING`。
- 双击胶囊标题区：`NORMAL ↔ FULLSCREEN`（Flyme/ColorOS 直觉）。
- 双击迷你 chip：`MINI ↔ FULLSCREEN`；单击：`MINI → NORMAL`。
- 记住每个应用上次的 `normalBounds`，下次开窗优先复用。
- 设置项：双击行为可关；关闭后只保留按钮。

验收：同一任务连续 10 次切换状态，bounds 不漂移；进程重启后至少保留最近 5 个应用的位置。

### P2：贴边挂起 + 边缘把手

- 拖到左/右边缘：Task 移出屏幕，只留一个 SystemUI 边缘把手/chip。
- 点把手：恢复贴边前的 bounds。
- 再拖到边缘：切换左右。
- 迷你窗拖到底部删除区：关闭（参考 OriginOS）。
- 关闭“贴边”开关时，行为退回普通 freeform。

验收：贴边后前台应用可正常交互；把手不挡状态栏/导航栏；横屏不越界。

### P3：侧边栏 + 轻量打开

- SystemUI 边缘侧边栏：应用列表、最近小窗、工具。
- 点击应用 → 直接 freeform；拖到屏幕中间 → 小窗；拖到顶部 → 全屏（HyperOS/One UI 语义）。
- 轻量打开：通知回复、设置跳转、分享到应用、文本操作，按开关决定是否小窗。
- 菜单管理：增删、排序、字母索引（Flyme）。

验收：侧边栏在游戏/视频上可呼出；不劫持正常返回手势；应用列表读取有权限降级。

### P4：最近任务 / 桌面深度适配

- 最近任务菜单“小窗 / 迷你 / 分屏”。
- 任务卡长按拖动：拖到中间小窗，拖到上下分屏（Pixel/LOS Launcher3）。
- 任务卡菜单显示当前窗口状态（小窗/迷你/全屏）。
- Pixel Launcher R8 类名变化时接 DexKit 指纹（可选）。

验收：LOS Launcher3 与 Pixel Launcher 都不崩；类名缺失时菜单不出现但开窗仍可用。

### P5：多窗 / App pairs（以后）

- 最多 2 个并行小窗；更多收进侧边浮标（ColorOS 双浮窗上限）。
- 小窗顶部菜单：全屏/分屏/关闭。
- App pairs 保存与一键恢复（One UI）。
- 多窗的资源/焦点/输入法冲突处理。

验收：双窗同时输入不抢焦点；关闭一个不影响另一个；低内存不炸 SystemUI。

### P6：打磨

- 开窗/关窗/贴边用 `Transitions` + 自研弹簧；从任务卡到小窗的共享元素。
- 模糊/圆角/阴影跟系统深浅色；无障碍焦点顺序；触感反馈。
- 每应用规则：禁止小窗、强制横屏、游戏模式。
- 崩溃救援：连续失败自动关闭模块；日志一键分享。

## 四、技术架构

```text
Launcher / 最近任务 / 侧边栏 / 通知
        │  开窗请求（taskId + bounds + source）
        ▼
SystemUI  WindowSessionStore
        │  state machine
        ├─ ShellTaskOrganizer + WindowContainerTransaction
        ├─ CaptionWindowDecoration（原生胶囊）
        ├─ EdgePanel / MiniChip（SystemUI 内部 View）
        └─ Transitions / SurfaceControl
        ▲
        │ 配置读取（XSharedPreferences）
Module App：设置 / 日志 / 规则 / 诊断
```

关键设计：

- **一个 Task 一个 Session**：所有 bounds 变更先过状态机，禁止各 Hook 各自 `setBounds`。
- **命令入口统一**：Launcher/侧边栏只发“请求”，SystemUI 校验来源后执行。
- **配置跨进程**：模块 App 写 `SharedPreferences`，SystemUI/Launcher 用 LSPosed `XSharedPreferences` 读；不搞两套配置。
- **动画分层**：Task 转场交给 `Transitions`；胶囊/chip 的微动效才用自研 `SpringAnimator`。
- **失败隔离**：每个 Hook 独立 try/catch；关键类找不到就降级，不阻塞系统启动。

## 五、执行方式（慢慢做）

每个 Cycle 只做一件事，固定节奏：

1. **设计**：写清楚状态、手势、失败降级、验收用例。
2. **实现**：一个分支一个功能；不混重构。
3. **静态冒烟**：`scripts/smoke.sh` 必须 26/26。
4. **真机冒烟**：P0 用例 + 新功能用例；抓 `logcat/dumpsys`。
5. **总结**：写 `docs/ITERATION_LOG.md`：做了什么、没做什么、真机结果、下一轮。
6. **发布**：只有真机通过才发 APK；仓库只放源码。

**Cycle 0（当前）**：P0 真机基线。没有设备就不继续堆功能。
**Cycle 1**：WindowSession + 双击尺寸循环。
**Cycle 2**：贴边挂起 + 边缘把手。
**Cycle 3**：侧边栏 + 轻量打开。
**Cycle 4**：最近任务/桌面。
**Cycle 5**：双窗/App pairs。

## 六、风险

- **WMShell 私有 API 随 ROM 变**：`caption_window_decor`、`WindowDecoration#inflateLayout`、`ShellTaskOrganizer` 都可能改；每个 Hook 必须有类名/布局名缺失降级。
- **SystemUI 进程崩溃代价高**：所有新 View/动画必须 try/catch；先做 feature flag。
- **Pixel Launcher 预编译/R8**：类名可能变；DexKit 是后手，不是首版前提。
- **多窗与输入法/焦点**：P5 才碰；不要提前把单窗状态机搞复杂。
- **没有真机**：当前环境无 adb/emulator；所有“功能通过”必须由真机冒烟背书。

## 七、参考来源

- Flyme 开放平台·应用小窗：https://open.flyme.cn/docs?id=267
- Flyme 10 更新（小窗一步贴边、双指缩放）：https://www.meizu.cn/thread/22207694
- Flyme 9 小窗模式 3.0：https://mwx-page.meizu.com/xiaocmoshi3.html
- 36Kr 五大 Android 小窗横评（Flyme 9 交互细节）：https://www.36kr.com/p/1218483351229060
- HyperOS 侧边栏：https://www.mi.com/global/discover/article?id=3345
- HyperOS 浮窗操作（第三方，仅作交互参考）：https://drfone.wondershare.com/android-xiaomi/use-floating-windows-multitasking-xiaomi-hyperos.html
- ColorOS 自由浮窗：https://www.coloros.com/instruction?id=850&version=ColorOS%2013
- ColorOS 17 双浮窗（视频信息，待验证）：https://www.iesdouyin.com/share/video/7700454510493273394
- OriginOS 小窗启动与操控：https://www.vivo.com.cn/service/questions/all?categoryId=156&questionId=1701
- One UI 多窗口/App pairs：https://www.samsung.com/us/support/answer/ANS10002022/
- One UI 弹出视图贴边：https://www.samsung.com.cn/one-ui/features/5-1-1/

## 八、下一步

1. 先用 0.5.0 跑 P0 真机冒烟，把日志发回；P0 不过不写新功能。
2. P0 通过后，按 P1 写 `WindowSession` 设计 + 状态机单测，再动 SystemUI。
3. 每个 Cycle 结束更新 `docs/ITERATION_LOG.md`，不攒大版本。
