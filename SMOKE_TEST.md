# native-window 0.5.0 冒烟测试

> 测试对象：`native-window-0.5.0-log-dark.apk`（versionCode 6，targetSdk 37）。
> 结论：**主机侧静态冒烟 26/26 通过；真机功能冒烟未执行**——当前环境没有 adb、没有 emulator，无法启动 PixelOS 17 / LineageOS 17。

## 一、已执行：APK 静态冒烟

脚本：`scripts/smoke.sh`  
命令：

```bash
ANDROID_HOME=/home/user/tools/android-sdk bash scripts/smoke.sh
```

结果：`SMOKE RESULT: 26 passed, 0 failed`

覆盖项：

- APK 存在、zip 完整、apksigner 签名有效；
- 包名 `io.github.wumisc.nativewindow`，versionName `0.5.0-log-dark`，targetSdk 37；
- `assets/xposed_init` 指向 `ModuleEntry`；
- Manifest：`MainActivity`、`OverlayService`、`LogProvider`、Provider authority、`xposedmodule` meta-data；
- DEX：`ModuleEntry`、`SystemServerHook`、`SystemUiHook`、`LauncherHook`、`NativeCaptionDecorator`、`NativeCapsuleView`、`LogCollector`、`LogProvider`、`Palette` 全部存在；
- 旧 `TaskChromeService` / `OpenReceiver` 已从 DEX 移除；
- `values-night` 夜间主题、日间 `Theme.Material.Light` 存在；
- APK 内无二次打包 APK。

## 二、必须在真机执行的 P0 冒烟

| # | 用例 | 步骤 | 期望 |
|---|---|---|---|
| 1 | 安装/启动 | `adb install -r native-window-0.5.0-log-dark.apk`，点图标 | 不闪退，主界面可见 |
| 2 | 深色跟随 | 系统切深色/浅色，重开 App | 背景、文字、状态栏图标同步切换，无白字白底 |
| 3 | 悬浮窗权限 | 首次点“显示小窗预览” | 跳系统授权；授权后预览出现 |
| 4 | 预览退出 | 点空白处 / “停止预览” | 悬浮窗消失，无残留 |
| 5 | 日志分享 | 点“收集并分享日志” | 出现分享面板；txt 可被其他 App 打开 |
| 6 | 日志 root 兜底 | 不授权 root 再试；授权 root 再试 | 日志分别写 `root=false` / `root=true`，不 ANR |
| 7 | 最近任务菜单 | 打开最近任务，点任务菜单 | 出现“小窗”项 |
| 8 | 小窗开窗 | 点“小窗” | 任务变为 freeform，不是全屏 |
| 9 | 原生胶囊 | 观察窗口顶部 | 有 HyperOS 胶囊；AOSP 三键不重复出现 |
| 10 | 胶囊拖动 | 拖胶囊空白区 | 胶囊和窗口一起动，无“先动胶囊后动窗口” |
| 11 | 缩放 | 拖边缘 | 窗口缩放，胶囊不脱离 |
| 12 | 迷你 | 点“—” | 缩为迷你 bounds；再点恢复 |
| 13 | 全屏 | 点“⛶” | 回全屏，caption 消失 |
| 14 | 关闭 | 点“×” | 任务从最近任务移除 |
| 15 | 深色胶囊 | 系统深色/浅色下各开一次小窗 | 胶囊深/浅切换，文字可读 |
| 16 | 侧边栏 | 预览侧边栏 | 图标、拖动、点击不崩 |
| 17 | 底角扇形 | 预览扇形 | 展开/选中/打开 App 不崩 |
| 18 | 作用域缺失 | 只勾 SystemUI，不勾桌面 | 不崩；菜单不出现时日志有 `TaskOverlayFactory not found` 或类名日志 |

## 三、失败时抓什么

1. 主界面点「收集并分享日志」，把 txt 发回；
2. 如果分享面板打不开，用 root 执行：

```bash
adb shell su -c 'logcat -d -v threadtime -t 2000 | grep -E "NativeWindow|LSPosed|AndroidRuntime|WindowManager"'
adb shell su -c 'dumpsys activity activities'
adb shell su -c 'dumpsys window windows'
```

3. 记录三个现象：**菜单有没有“小窗” / 点了是否 freeform / 胶囊和窗口是否一起动**。

## 四、判定

- 静态冒烟已通过，能保证包结构、入口、Provider、深色资源和关键类没有漏打包。
- 功能冒烟必须在 PixelOS 17 / LOS 17 真机上执行；当前没有设备，不能把静态检查冒充成功能通过。
