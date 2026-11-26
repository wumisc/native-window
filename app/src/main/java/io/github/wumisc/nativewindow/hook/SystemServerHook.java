package io.github.wumisc.nativewindow.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** system_server 最小门禁：只打开 AOSP freeform 相关开关，失败不拖垮系统。 */
public final class SystemServerHook {
    private static final String TAG = "NativeWindow/SS";

    private SystemServerHook() {}

    public static void install(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> atms = XposedHelpers.findClass(
                    "com.android.server.wm.ActivityTaskManagerService", lp.classLoader);
            XposedBridge.hookAllConstructors(atms, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    patch(param.thisObject);
                }
            });
            XposedHelpers.findAndHookMethod(atms, "onSystemReady", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    patch(param.thisObject);
                }
            });
            XposedBridge.log(TAG + ": hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": install failed " + t);
        }
    }

    private static void patch(Object service) {
        setBool(service, "mSupportsMultiWindow");
        setBool(service, "mSupportsFreeformWindowManagement");
        setBool(service, "mForceResizableActivities");
    }

    private static void setBool(Object target, String field) {
        try {
            XposedHelpers.setBooleanField(target, field, true);
            XposedBridge.log(TAG + ": " + field + "=true");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + field + " failed " + t);
        }
    }
}
