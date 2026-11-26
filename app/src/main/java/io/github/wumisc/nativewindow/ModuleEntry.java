package io.github.wumisc.nativewindow;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import io.github.wumisc.nativewindow.hook.LauncherHook;
import io.github.wumisc.nativewindow.hook.LauncherPackages;
import io.github.wumisc.nativewindow.hook.SystemServerHook;

/**
 * LSPosed 入口：system_server 只做 freeform 门禁；Launcher / Quickstep 做最近任务与桌面入口。
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
        if (LauncherPackages.isLauncher(lpparam.packageName)) {
            XposedBridge.log(TAG + ": launcher " + lpparam.packageName);
            LauncherHook.install(lpparam);
        }
    }
}
