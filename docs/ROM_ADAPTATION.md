# PixelOS / LineageOS 适配说明

## 目标范围

- **PixelOS 17**：AOSP 17 基线，桌面优先识别 `com.google.android.apps.nexuslauncher` 与 `com.android.quickstep`。
- **LineageOS 24 / Android 17**：桌面优先识别 `com.android.launcher3`，兼容别名 `org.lineageos.launcher`。
- 识别不到具体 ROM 时回退 AOSP 通用路径；不在未知 ROM 上强行启用厂商私有 Hook。

## 识别方式

`core/RomProfile.java` 只读保守信号：

- `ro.pixelos.version`、`ro.lineageos.version`（反射 `SystemProperties`，读取失败不报错）；
- `Build.HOST`、`Build.PRODUCT`、`Build.DISPLAY`、`Build.MANUFACTURER`；
- 识别结果分为 `PIXELOS`、`LINEAGEOS`、`AOSP`、`UNKNOWN`。

不要把厂商私有类名写死成默认路径；所有类不存在时 fail-open，只在日志里记录。

## 适配矩阵

| 层级 | PixelOS / Pixel Launcher | LineageOS / Launcher3 | 公共兜底 |
| --- | --- | --- | --- |
| system_server | 打开多窗、freeform、强制可缩放；识别 PixelProps 任务权限绕过 | 同一套 AOSP 门禁；非可缩放应用开关按画像启用 | ATMS 构造、`retrieveSettings`、`onSystemReady` 后重复 patch，防止设置被覆盖 |
| 权限放行 | 模块 UID、Pixel Launcher / Quickstep UID、system/root | 模块 UID、Launcher3 / Trebuchet UID、system/root | 只放行最近任务与任务管理相关权限，不全局放开权限 |
| 最近任务入口 | `com.android.quickstep.TaskOverlayFactory`；`com.android.launcher3.popup.RemoteActionShortcut` / Quickstep 别名 | 同样按 Quickstep 与 Launcher3 两个候选类查找 | 类名缺失时记录日志，不插入不可用菜单 |
| 虚拟屏窗口 | 默认宽度 82%、高度 56%，贴边阈值 60dp | 默认宽度 80%、高度 54%，贴边阈值 56dp | 圆角、拖动、缩放、IME、旋转、多窗置顶、贴边迷你走公共链路 |
| SystemUI | 识别 Pixel Launcher / Quickstep UID 为合法发起方 | 识别 Launcher3 / Trebuchet UID 为合法发起方 | 发起方不合法时不改任务边界或窗口模式 |

## LSPosed 作用域

`xposed_scope` 当前包含：

- `android`（system_server，虚拟屏引擎与门禁）
- `com.android.systemui`
- `com.android.launcher3`
- `org.lineageos.launcher`
- `com.android.quickstep`
- `com.google.android.apps.nexuslauncher`
- `com.miui.home`（保留给 HyperOS 调试，类原生默认不生效）

## 仍需真机日志背书

1. PixelOS 上 `TaskOverlayFactory` 与 `RemoteActionShortcut` 的实际混淆/重命名类名。
2. LineageOS Launcher3 是否保留 Quickstep 最近任务菜单 Hook 点；若被 Trebuchet 定制替换，需要另找菜单工厂。
3. 虚拟屏 `TRUSTED`、系统装饰、触摸注入在两类 ROM 上是否被 WMS 放行。
4. 本地 IME policy 能否让候选词与输入法面板落在虚拟屏。
5. PixelPropsUtils 是否在目标 PixelOS 构建中存在；不存在时权限绕过会自动跳过。

## 边界

- 不复制 MIUI/HyperOS 私有栈；厂商适配只能作为可选层。
- 不承诺旧版本 LOS 15/16 或 Android 14 以下设备。
- 本仓库当前主线是虚拟屏镜像；原生 freeform 后端保留代码，但不作为类原生 17 的默认路径。
