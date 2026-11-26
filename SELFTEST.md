# 0.6.0-selftest 自动自检说明

## 怎么用

1. 安装 `native-window-0.6.0-selftest.apk`。
2. 确认 LSPosed 已启用模块，作用域勾选：**系统框架（android）**、**SystemUI（com.android.systemui）**、**桌面（Pixel Launcher / Launcher3）**。
3. 重启 SystemUI 或重启手机。
4. 打开「类原生小窗」图标，**不要点任何按钮**。

打开后会自动进入「自动自检」页，流程是：

```text
打开主界面
  → 280ms 后自动进入自检页
  → 700ms 后把自己的 task 交给 SystemUI 改成 FREEFORM
  → 等 4.8s 观察任务尺寸是否变小
  → 变小：判定 freeform 成功，2s 后恢复全屏
  → 没变小：判定失败，恢复全屏
  → 显示自检结果
```

## 结果怎么看

- **核心链路通过**：任务尺寸真的变小了，说明 `SystemServerHook` 的 freeform 门禁、`SystemUiHook` 的 `ShellTaskOrganizer` + `WindowContainerTransaction` 都生效了。
- **核心链路未通过**：任务仍全屏。按顺序排查：
  1. LSPosed 模块是否启用；
  2. 作用域是否包含 `android` / `com.android.systemui`；
  3. 是否重启过 SystemUI；
  4. 模块 App 是否被系统限制后台/自启动（自检广播来自模块 UID）；
  5. 用主界面「收集并分享日志」，看 `NativeWindow/SUI: force-small receiver registered` 和 `applyWindowMode failed`。
- **设备/构建/任务/屏幕**：只是环境信息；任务 `taskId` 为 -1 时无法自检。
- **悬浮窗权限**：只影响主界面的假预览，不影响真实小窗。

## 这个版本测不到什么

- **胶囊是否挂上**：胶囊在 SystemUI 进程的 `CaptionWindowDecoration` 里，自检页只测 task 尺寸；胶囊要看日志 `NativeWindow/Caption: capsule attached`。
- **第三方 App 开窗**：自检用的是模块自己的 task，避免自动拉起别人的 App。第三方 App 的 non-resizable / 布局重排问题仍需真机点一次最近任务。
- **拖动/缩放跟手**：需要真实触摸，自动化做不了。
- **贴边/侧边栏/多窗**：还没实现，不在这个版本里。

## 构建与校验

```bash
ANDROID_HOME=/home/user/tools/android-sdk JAVA_HOME=/home/user/tools/jdk bash scripts/build.sh
ANDROID_HOME=/home/user/tools/android-sdk bash scripts/smoke.sh
```

当前静态冒烟：28/28。

## 版本

- versionCode 7
- versionName 0.6.0-selftest
- 包名 io.github.wumisc.nativewindow
- APK：`dist/native-window-0.6.0-selftest.apk`
- sha256：`3ee746abe39c7bfa23ce7ac6528952869ad45304a00ce99f99f4c956e431b0f8`
