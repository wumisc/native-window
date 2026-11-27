# 米窗路线：虚拟屏小窗

## 为什么换路线

0.9.x 一直走“把真实 task 改成 freeform + 补 SystemUI 外壳”。在 API 35 上这条路缺 `CaptionWindowDecoration`，只能自己往 SystemUI 塞 `TYPE_APPLICATION_OVERLAY`；在 API 37 上又依赖 LSPosed 作用域和 SystemUI 进程存活。用户看到的仍然是“任务全屏、外壳没贴上”，也就是“预览”。

YAMF（另一个米窗）证明另一条路是可行的：**不碰 WMS freeform**，由 system_server 创建一块虚拟屏，把 Activity 启动到这块屏，再用悬浮卡片里的 TextureView 镜像画面，触摸按虚拟屏 `displayId` 注入。这样窗口的标题栏、拖动、缩放全部由模块自己控制，不依赖 ROM 的 caption。

参考：

- YAMF：`https://github.com/duzhaokun123/YAMF`，GPL-3.0，核心 `AppWindow.kt` / `YAMFManager.kt` / `HookSystem.kt` / `HookLauncher.kt`。
- Free（Flyme 小窗）：`https://github.com/Hoerire/Free`，GPL-3.0，扇形菜单、迷你窗、贴边状态机作为后续交互参考。

## 当前实现（1.0.0-alpha2）

### 进程与职责

| 进程 | 类 | 职责 |
| --- | --- | --- |
| system_server | `VirtualWindowEngine` | 初始化、注册广播、创建/回收窗口 |
| system_server | `VirtualWindow` | 虚拟屏、悬浮卡片、标题栏、拖动、缩放、底栏 |
| system_server | `VirtualWindowInput` | 触摸/按键按 `displayId` 注入 |
| App | `AppPickerActivity` | 选择应用，发广播给 system_server |
| App | `VirtualWindowClient` | 封装 `OPEN_VIRTUAL_WINDOW` / `CLOSE_VIRTUAL_WINDOWS` |
| Launcher | `LauncherHook` | 最近任务菜单“小窗”，拿 `taskView.getTask().key.id`，3/4 参数构造 `RemoteActionShortcut` |

### 开窗链路

1. `AppPickerActivity` 调 `VirtualWindowClient.open()`，广播 `io.github.wumisc.nativewindow.action.OPEN_VIRTUAL_WINDOW` 到 `package=android`。
2. `VirtualWindowEngine` 在 AMS `systemReady` 后初始化，收到广播，主线程创建 `VirtualWindow`。
3. `DisplayManager.createVirtualDisplay("nativewindow-…", w, h, density, null, 1668)`，flags = SECURE + ROTATES_WITH_CONTENT + SHOULD_SHOW_SYSTEM_DECORATIONS + TRUSTED。
4. `WindowManager.addView` 添加 `TYPE_APPLICATION_OVERLAY` 卡片。
5. `startActivityAsUser` + `ActivityOptions.setLaunchDisplayId(displayId)` 启动目标 Activity；从最近任务进入则 `moveRootTaskToDisplay(taskId, displayId)`。
6. TextureView 的 `SurfaceTexture` 就绪后 `virtualDisplay.setSurface(new Surface(st))`，画面开始镜像。
7. `getAllRootTaskInfosOnDisplay(displayId)` 找到 taskId，`getTaskDescription(taskId)` 更新图标/标题。

### 交互

- 顶部标题栏：拖动整窗；双击在原始尺寸/迷你尺寸之间切换；右上角关闭；键盘按钮切换输入法焦点。
- 内容区：触摸事件按 `displayId` 注入，应用收到的是真实输入；按下已有窗口时把该窗口提到最前。
- 右下角 `◢`：拖动改内容区尺寸，TextureView 尺寸变化后 `virtualDisplay.resize()`，应用跟随重排。
- 底栏：返回（KEYCODE_BACK）、桌面（KEYCODE_HOME）、旋转（交换虚拟屏宽高）、全屏（`moveRootTaskToDisplay(taskId, 0)` 后释放虚拟屏、不删任务）、关闭（`removeTask` + 释放虚拟屏）。
- 贴边：标题栏拖到左右边缘松手后收成侧边把手；点把手恢复原位置。位置/尺寸切换走弹簧动画。
- 多窗：同一包名或同一 taskId 再次开窗时复用已有窗口并置顶；引擎保留窗口列表。
- 任务自己退出：看门狗轮询虚拟屏根任务，为空就收卡片。

### 安全边界

- 只接受 system/root 或模块自身 UID 的广播；模块 UID 解析失败时退化为接受广播并打日志。
- 不修改系统分区，不注入系统 UI 资源，不依赖 HyperOS 私有类。
- 单窗失败只影响当前卡片，不阻断系统启动（所有 Hook fail-open）。

## 已知限制（alpha2）

1. **IME 待真机背书**：已设置本地 IME policy，并提供键盘按钮临时移除 `FLAG_NOT_FOCUSABLE` / `FLAG_ALT_FOCUSABLE_IM`；但 system_server overlay 能否拿到焦点、候选词和面板是否落在虚拟屏，仍需日志确认。
2. **横屏/旋转仍待真机确认**：已监听默认屏旋转并重新夹取窗口位置；通过动态代理注册任务栈回调，识别应用请求的横屏/竖屏并自动交换虚拟屏宽高；传感器自动旋转、反向横屏等组合方向还未完全覆盖。
3. **多窗层叠仍是基础版**：支持复用、置顶和列表，尚未做数量上限、失焦降级、独立层级通道和多窗手势切换。
4. **贴边/迷你未持久化**：贴边把手和迷你尺寸只保存在内存，重启或关闭后不恢复；熄屏保活、边缘把手拖动还未做。
5. **桌面入口**：最近任务菜单按 YAMF 的字段和构造函数修了，但还没有在类原生真机确认菜单项出现。

## 测试步骤

1. 安装 `native-window-1.0.0-alpha2.apk`。
2. LSPosed 作用域至少勾选：系统框架（`android`）、Pixel Launcher / Launcher3；如果用最近任务入口，桌面必须勾选。
3. 重启系统框架（或重启手机）。
4. 打开模块 → “选择应用开小窗” → 点“设置”。
5. 预期：选择页退出，屏幕中央出现一张 82% 宽、56% 高的悬浮卡片；标题栏显示“设置”和图标；卡片里是真实设置页面。
6. 拖标题栏移动；拖右下角 `◢` 缩放；点“返回/桌面/全屏/关闭”。
7. 查看 LSPosed 日志关键字：`NativeWindow/VWE`、`NativeWindow/VW`、`NativeWindow/VWI`。

失败时优先抓：

```bash
adb logcat -s "NativeWindow/VWE" "NativeWindow/VW" "NativeWindow/VWI" AndroidRuntime
```
