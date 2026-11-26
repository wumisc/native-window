package io.github.wumisc.nativewindow.hook;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * HyperOS 4 的 system_server 门禁。
 *
 * HyperOS 4 虽然仍是 Android 17，但 freeform 不是纯 AOSP 路径：
 * - 真正的开关在 android.util.MiuiMultiWindowUtils.supportFreeform / isForceResizeable；
 * - 黑名单在 android.util.MiuiMultiWindowAdapter；
 * - 窗口栈是 com.android.server.wm.MiuiFreeFormActivityStack /
 *   MiuiFreeFormStackDisplayStrategy。
 *
 * 这里只做“允许进入 freeform”，不碰 AOSP 的 WCT。类不存在就跳过，fail-open。
 */
public final class HyperOsSystemServerHook {
    private static final String TAG = "NativeWindow/HyperOS-SS";

    private HyperOsSystemServerHook() {}

    public static void install(XC_LoadPackage.LoadPackageParam lp) {
        if (!HyperOsDetect.isHyperOs(lp.classLoader)) return;
        try {
            hookReturn("android.util.MiuiMultiWindowUtils", lp.classLoader,
                    "supportFreeform", true);
            hookReturn("android.util.MiuiMultiWindowUtils", lp.classLoader,
                    "isForceResizeable", true);
            hookReturn("com.android.server.wm.Task", lp.classLoader,
                    "isResizeable", true);
            clearBlacklists(lp.classLoader);
            hookFreeformLimit(lp.classLoader);
            XposedBridge.log(TAG + ": HyperOS freeform gates installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": install failed " + t);
        }
    }

    private static void hookReturn(String className, ClassLoader cl,
            String method, boolean value) {
        try {
            Class<?> cls = XposedHelpers.findClassIfExists(className, cl);
            if (cls == null) return;
            XposedBridge.hookAllMethods(cls, method, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    param.setResult(value);
                }
            });
            XposedBridge.log(TAG + ": " + className + "#" + method + "=true");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + method + " failed " + t);
        }
    }

    private static void clearBlacklists(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClassIfExists(
                    "android.util.MiuiMultiWindowAdapter", cl);
            if (cls == null) return;
            XposedBridge.hookAllMethods(cls, "getFreeformBlackList", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    param.setResult(new ArrayList<String>());
                }
            });
            XposedBridge.hookAllMethods(cls, "getAbnormalFreeformBlackList", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    param.setResult(new ArrayList<String>());
                }
            });
            XposedBridge.hookAllMethods(cls, "getStartFromFreeformBlackList",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(new ArrayList<String>());
                        }
                    });
            XposedBridge.log(TAG + ": freeform blacklists cleared");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": blacklists failed " + t);
        }
    }

    private static void hookFreeformLimit(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClassIfExists(
                    "com.android.server.wm.MiuiFreeFormStackDisplayStrategy", cl);
            if (cls == null) return;
            XposedBridge.hookAllMethods(cls, "getMaxMiuiFreeFormStackCount",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(256);
                        }
                    });
            XposedBridge.hookAllMethods(cls, "getMaxMiuiFreeFormStackCountForFlashBack",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(256);
                        }
                    });
            XposedBridge.hookAllMethods(cls, "shouldStopStartFreeform", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    param.setResult(false);
                }
            });
            XposedBridge.log(TAG + ": freeform count limit opened");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": freeform limit failed " + t);
        }
    }
}
