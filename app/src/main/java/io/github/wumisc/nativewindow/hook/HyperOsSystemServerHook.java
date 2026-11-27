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
            hookActivityStarter(lp.classLoader);
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
     * 真正可用的 HyperOS 开小窗切入点：ActivityStarter#executeRequest。
     *
     * HyperCeiler 的 OpenAppInFreeForm 已验证：
     * - request.intent 是目标 Intent；
     * - request.callingPackage 是调用方；
     * - ActivityStarter 自己有 setActivityOptions(Bundle)；
     * - MiuiMultiWindowUtils.getActivityOptions(context, pkg, true, false) 返回
     *   HyperOS 认的 freeform options。
     *
     * 之前挂 ActivityStarterInjector 是猜错了类，所以 0.8.0 一点反应都没有。
     */
    private static void hookActivityStarter(ClassLoader cl) {
        try {
            Class<?> starter = XposedHelpers.findClassIfExists(
                    "com.android.server.wm.ActivityStarter", cl);
            if (starter == null) {
                XposedBridge.log(TAG + ": ActivityStarter not found");
                return;
            }
            XposedBridge.hookAllMethods(starter, "executeRequest", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length < 1) return;
                        Object request = param.args[0];
                        Object intentObj = XposedHelpers.getObjectField(request, "intent");
                        if (!(intentObj instanceof android.content.Intent)) return;
                        android.content.Intent intent = (android.content.Intent) intentObj;
                        if (!intent.getBooleanExtra(
                                "io.github.wumisc.nativewindow.extra.FREEFORM", false)) return;
                        android.content.ComponentName cn = intent.getComponent();
                        String pkg = cn != null ? cn.getPackageName() : intent.getPackage();
                        if (pkg == null) return;

                        Object service = XposedHelpers.getObjectField(
                                param.thisObject, "mService");
                        Object context = XposedHelpers.getObjectField(service, "mContext");
                        Class<?> utils = XposedHelpers.findClass(
                                "android.util.MiuiMultiWindowUtils", cl);
                        Object options = XposedHelpers.callStaticMethod(utils,
                                "getActivityOptions", context, pkg, true, false);
                        if (options instanceof android.app.ActivityOptions) {
                            XposedHelpers.callMethod(param.thisObject,
                                    "setActivityOptions",
                                    ((android.app.ActivityOptions) options).toBundle());
                            XposedBridge.log(TAG + ": executeRequest -> freeform "
                                    + pkg);
                        } else {
                            XposedBridge.log(TAG + ": getActivityOptions returned "
                                    + options + " for " + pkg);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": executeRequest freeform failed " + t);
                    }
                }
            });
            XposedBridge.log(TAG + ": ActivityStarter.executeRequest hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ActivityStarter hook failed " + t);
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
