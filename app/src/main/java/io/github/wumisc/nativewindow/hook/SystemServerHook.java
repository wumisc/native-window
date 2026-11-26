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
 * system_server 门禁。
 *
 * 源码核对（PixelOS 17 / LineageOS 24.0 == Android 17）：
 * - ActivityTaskManagerService#retrieveSettings 会把
 *   mSupportsMultiWindow / mSupportsFreeformWindowManagement / mForceResizableActivities
 *   按 config 重算；只在构造里改会被覆盖，所以必须挂 retrieveSettings 之后。
 * - Task.supportsFreeformInDisplayArea 同时要求：
 *     TaskDisplayArea.isWindowingModeSupported(FREEFORM, mSupportsMultiWindow,
 *         mSupportsFreeformWindowManagement, mSupportsPictureInPicture)
 *     && supportsMultiWindowInDisplayArea(tda)
 * - 非 resizeable 的国产 App 要能进小窗，还需要
 *   mForceResizableActivities / mDevEnableNonResizableMultiWindow / mSupportsNonResizableMultiWindow。
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
        try {
            Class<?> atms = XposedHelpers.findClass(
                    "com.android.server.wm.ActivityTaskManagerService", lp.classLoader);
            XposedBridge.hookAllConstructors(atms, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    patch(param.thisObject);
                }
            });
            // 关键：retrieveSettings 会覆盖字段，必须挂它之后。
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

    /**
     * 0.4.0 的开窗在 Launcher 进程完成；这里仍补权限，兼容旧版回退路径。
     * Launcher 进程本来就有该权限，不依赖这里；这里只对模块 UID 放行。
     */
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
                            && !PERM_MANAGE_ACTIVITY_TASKS.equals(permission)) {
                        return;
                    }
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

    /**
     * PixelOS 在 ActivityManagerService / ActivityTaskManagerService 里加了
     * PixelPropsUtils.shouldBypassTaskPermission(uid)。把模块 UID 也纳入，避免
     * PixelOS 某些 task 接口在 enforce 前就被它拦掉。
     */
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
            final Method target = m;
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 1) return;
                    int uid = (Integer) param.args[0];
                    if (sModuleUid > 0 && uid == sModuleUid) {
                        param.setResult(true);
                    }
                }
            });
            XposedBridge.log(TAG + ": PixelProps task bypass hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": PixelProps bypass failed " + t);
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
