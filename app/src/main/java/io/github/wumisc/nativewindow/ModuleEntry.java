package io.github.wumisc.nativewindow;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import io.github.wumisc.nativewindow.hook.LauncherHook;
import io.github.wumisc.nativewindow.hook.LauncherPackages;
import io.github.wumisc.nativewindow.hook.SystemServerHook;
import io.github.wumisc.nativewindow.hook.SystemUiHook;

/**
 * LSPosed 入口：
 * - system_server：打开 freeform 门禁，补 task 权限；
 * - SystemUI / WMShell：保留原生 caption 并在 caption 根 View 里挂 HyperOS 胶囊，执行 WCT；
 * - Launcher：最近任务菜单注入，真正的 freeform 启动在 Launcher 进程完成。
 * 所有 Hook 都是 fail-open，单个类不存在不会影响系统启动。
 */
public class ModuleEntry implements IXposedHookLoadPackage {
    private static final String TAG = "NativeWindow";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if ("android".equals(lpparam.packageName)) {
            SystemServerHook.install(lpparam);
            return;
        }
        if ("com.android.systemui".equals(lpparam.packageName)) {
            XposedBridge.log(TAG + ": systemui");
            SystemUiHook.install(lpparam);
            return;
        }
        if (LauncherPackages.isLauncher(lpparam.packageName)) {
            XposedBridge.log(TAG + ": launcher " + lpparam.packageName);
            LauncherHook.install(lpparam);
        }
    }
}
