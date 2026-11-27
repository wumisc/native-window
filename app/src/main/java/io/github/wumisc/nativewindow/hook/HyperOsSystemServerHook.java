package io.github.wumisc.nativewindow.hook;

import java.lang.reflect.Method;
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
            hookProbeLaunchOptions(lp.classLoader);
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

    /**
     * HyperOS 的 ActivityStarterInjector 会在最后一步按“是否在白名单”重算
     * ActivityOptions，App 自己传的 freeform 可能被改回 fullscreen。
     * 只对我们自己的 ProbeActivity 强制写 FREEFORM，用来验证系统链路。
     */
    private static void hookProbeLaunchOptions(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClassIfExists(
                    "com.android.server.wm.ActivityStarterInjector", cl);
            if (cls == null) {
                XposedBridge.log(TAG + ": ActivityStarterInjector not found");
                return;
            }
            XposedBridge.hookAllMethods(cls, "modifyLaunchActivityOptionIfNeed",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (param.args == null || param.args.length < 8) return;
                                Object intentObj = param.args[5];
                                if (!(intentObj instanceof android.content.Intent)) return;
                                android.content.Intent intent = (android.content.Intent) intentObj;
                                if (intent.getComponent() == null) return;
                                if (!"io.github.wumisc.nativewindow".equals(
                                        intent.getComponent().getPackageName())) return;
                                if (!intent.getComponent().getClassName()
                                        .endsWith("selftest.ProbeActivity")) return;
                                Object res = param.getResult();
                                android.app.ActivityOptions options =
                                        res instanceof android.app.ActivityOptions
                                                ? (android.app.ActivityOptions) res
                                                : android.app.ActivityOptions.makeBasic();
                                android.graphics.Rect rect = freeformRect();
                                XposedHelpers.callMethod(options,
                                        "setLaunchWindowingMode", 5);
                                options.setLaunchBounds(rect);
                                try {
                                    XposedHelpers.callMethod(options,
                                            "setMiuiConfigFlag", 2);
                                } catch (Throwable ignored) {}
                                param.setResult(options);
                                XposedBridge.log(TAG + ": forced probe freeform "
                                        + rect.flattenToString());
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": probe options failed " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": probe launch options hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": probe launch hook failed " + t);
        }
    }

    private static android.graphics.Rect freeformRect() {
        android.graphics.Rect r = new android.graphics.Rect(48, 240, 1032, 2200);
        try {
            Class<?> utils = XposedHelpers.findClass(
                    "android.util.MiuiMultiWindowUtils", null);
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object ctx = XposedHelpers.callStaticMethod(at, "currentApplication");
            if (ctx instanceof android.content.Context) {
                Object rect = XposedHelpers.callStaticMethod(
                        utils, "getFreeformRect", ctx);
                if (rect instanceof android.graphics.Rect) return new android.graphics.Rect(
                        (android.graphics.Rect) rect);
            }
        } catch (Throwable ignored) {}
        return r;
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
