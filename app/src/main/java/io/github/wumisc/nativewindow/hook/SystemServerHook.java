package io.github.wumisc.nativewindow.hook;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Binder;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * system_server 门禁：PixelOS 17 / LineageOS 24.0（Android 17）的 freeform 开关。
 *
 * - retrieveSettings 会按 config 重算 mSupportsFreeformWindowManagement 等字段，
 *   所以必须在它之后 patch，否则开机后被覆盖。
 * - 非 resizeable App 还要打开 mForceResizableActivities、
 *   mDevEnableNonResizableMultiWindow、mSupportsNonResizableMultiWindow。
 * - AOSP 的带标记启动由 AospFreeformHook 负责；HyperOS 的由 HyperOsSystemServerHook 负责。
 */
public final class SystemServerHook {
    private static final String TAG = "NativeWindow/SS";
    private static final String MODULE_PKG = "io.github.wumisc.nativewindow";
    private static final String PERM_START_FROM_RECENTS =
            "android.permission.START_TASKS_FROM_RECENTS";
    private static final String PERM_MANAGE_ACTIVITY_TASKS =
            "android.permission.MANAGE_ACTIVITY_TASKS";

    private static volatile int sModuleUid = -1;

    private SystemServerHook() {}

    public static void install(XC_LoadPackage.LoadPackageParam lp) {
        HyperOsSystemServerHook.install(lp);
        AospFreeformHook.install(lp);
        try {
            Class<?> atms = XposedHelpers.findClass(
                    "com.android.server.wm.ActivityTaskManagerService", lp.classLoader);
            XposedBridge.hookAllConstructors(atms, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    patch(param.thisObject);
                }
            });
            XposedHelpers.findAndHookMethod(atms, "retrieveSettings",
                    ContentResolver.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            patch(param.thisObject);
                        }
                    });
            XposedHelpers.findAndHookMethod(atms, "onSystemReady", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    patch(param.thisObject);
                }
            });
            hookPermissionBypass(lp);
            hookPixelPropsBypass(lp);
            hookVirtualWindow(lp);
            XposedBridge.log(TAG + ": hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": install failed " + t);
        }
    }

    private static void patch(Object service) {
        setBool(service, "mSupportsMultiWindow");
        setBool(service, "mSupportsFreeformWindowManagement");
        setBool(service, "mForceResizableActivities");
        setBool(service, "mDevEnableNonResizableMultiWindow");
        setInt(service, "mSupportsNonResizableMultiWindow", 1);
        resolveModuleUid(service);
    }

    private static void resolveModuleUid(Object service) {
        if (sModuleUid > 0) return;
        try {
            Object ctx = XposedHelpers.getObjectField(service, "mContext");
            if (ctx instanceof Context) {
                sModuleUid = ((Context) ctx).getPackageManager()
                        .getPackageUid(MODULE_PKG, 0);
                XposedBridge.log(TAG + ": moduleUid=" + sModuleUid);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": resolve module uid failed " + t);
        }
    }

    private static void hookPermissionBypass(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> ams = XposedHelpers.findClass(
                    "com.android.server.am.ActivityManagerService", lp.classLoader);
            XposedBridge.hookAllMethods(ams, "enforceCallingPermission", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 1) return;
                    if (!(param.args[0] instanceof String)) return;
                    String permission = (String) param.args[0];
                    if (!PERM_START_FROM_RECENTS.equals(permission)
                            && !PERM_MANAGE_ACTIVITY_TASKS.equals(permission)) return;
                    if (sModuleUid > 0 && Binder.getCallingUid() == sModuleUid) {
                        param.setResult(null);
                    }
                }
            });
            XposedBridge.log(TAG + ": AMS permission bypass hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": permission bypass failed " + t);
        }
    }

    private static void hookPixelPropsBypass(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> cls = XposedHelpers.findClassIfExists(
                    "com.android.internal.util.custom.PixelPropsUtils", lp.classLoader);
            if (cls == null) return;
            Method m = null;
            for (Method candidate : cls.getDeclaredMethods()) {
                if ("shouldBypassTaskPermission".equals(candidate.getName())
                        && candidate.getParameterTypes().length == 1
                        && candidate.getParameterTypes()[0] == int.class) {
                    m = candidate;
                    break;
                }
            }
            if (m == null) return;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 1) return;
                    int uid = (Integer) param.args[0];
                    if (sModuleUid > 0 && uid == sModuleUid) param.setResult(true);
                }
            });
            XposedBridge.log(TAG + ": PixelProps task bypass hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": PixelProps bypass failed " + t);
        }
    }

    private static void hookVirtualWindow(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> ams = XposedHelpers.findClass(
                    "com.android.server.am.ActivityManagerService", lp.classLoader);
            XposedBridge.hookAllMethods(ams, "systemReady", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    VirtualWindowEngine.init(param.thisObject);
                }
            });
            XposedBridge.log(TAG + ": VirtualWindowEngine hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": VirtualWindowEngine hook failed " + t);
        }
    }

    private static void setBool(Object target, String field) {
        try {
            XposedHelpers.setBooleanField(target, field, true);
            XposedBridge.log(TAG + ": " + field + "=true");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + field + " failed " + t);
        }
    }

    private static void setInt(Object target, String field, int value) {
        try {
            XposedHelpers.setIntField(target, field, value);
            XposedBridge.log(TAG + ": " + field + "=" + value);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + field + " failed " + t);
        }
    }
}
