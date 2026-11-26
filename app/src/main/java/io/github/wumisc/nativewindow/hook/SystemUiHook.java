package io.github.wumisc.nativewindow.hook;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.WindowManager;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * SystemUI / WMShell 深度适配。
 *
 * 源码核对（Android 17）：
 * - freeform 任务的原生 caption 由 CaptionWindowDecorViewModel 创建，
 *   视图是 com.android.wm.shell.windowdecor.CaptionWindowDecoration，根 View 是
 *   WindowDecorLinearLayout，布局 caption_window_decor。
 * - CaptionWindowDecoration 已经把 Task surface 的圆角、阴影、crop、position 一起处理；
 *   这是“窗口本体 + chrome 同一个 Surface”的正确切入点。
 * - 任务边界修改走 ShellTaskOrganizer#applyTransaction(WindowContainerTransaction)。
 *
 * 0.4.0 不再使用 App 悬浮窗 chrome：直接把胶囊塞进原生 caption 的 View 树，
 * 拖动/缩放继续交给 CaptionTouchEventListener，避免“胶囊先动、窗口后动”。
 */
public final class SystemUiHook {
    private static final String TAG = "NativeWindow/SUI";

    private static volatile Object sOrganizer;
    private static volatile Context sContext;
    private static final android.util.SparseArray<Rect> sNormalBounds =
            new android.util.SparseArray<>();

    private SystemUiHook() {}

    public static void install(XC_LoadPackage.LoadPackageParam lp) {
        hookSystemUiApplication(lp);
        hookTaskOrganizer(lp);
        hookWindowDecorInflation(lp);
    }

    private static void hookSystemUiApplication(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> cls = XposedHelpers.findClassIfExists(
                    "com.android.systemui.application.impl.SystemUIApplicationImpl",
                    lp.classLoader);
            if (cls == null) return;
            XposedHelpers.findAndHookMethod(cls, "onCreate", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    sContext = (Context) param.thisObject;
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook SystemUI app failed " + t);
        }
    }

    private static void hookTaskOrganizer(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> cls = XposedHelpers.findClassIfExists(
                    "com.android.wm.shell.ShellTaskOrganizer", lp.classLoader);
            if (cls == null) return;
            XposedBridge.hookAllConstructors(cls, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    sOrganizer = param.thisObject;
                }
            });
            XposedBridge.hookAllMethods(cls, "onTaskVanished", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length >= 1) {
                        sNormalBounds.remove(intField(param.args[0], "taskId", -1));
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": task organizer hook failed " + t);
        }
    }

    /** 在原生 caption 的 inflateLayout 之后，把 HyperOS 胶囊塞进它的根 View。 */
    private static void hookWindowDecorInflation(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> cls = XposedHelpers.findClassIfExists(
                    "com.android.wm.shell.windowdecor.WindowDecoration", lp.classLoader);
            if (cls == null) return;
            XposedBridge.hookAllMethods(cls, "inflateLayout", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length < 2) return;
                        if (!(param.args[0] instanceof Context)) return;
                        if (!(param.args[1] instanceof Integer)) return;
                        Object result = param.getResult();
                        if (!(result instanceof View)) return;
                        Context ctx = (Context) param.args[0];
                        int layoutId = (Integer) param.args[1];
                        String name = ctx.getResources().getResourceEntryName(layoutId);
                        if ("caption_window_decor".equals(name)) {
                            NativeCaptionDecorator.decorate(param.thisObject, (View) result);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": decorate caption failed " + t);
                    }
                }
            });
            XposedBridge.log(TAG + ": native caption hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": caption hook failed " + t);
        }
    }

    /** 原生 caption 的“—”：第一次缩到迷你 bounds，第二次恢复。 */
    static void toggleMini(int taskId) {
        Object info = getInfo(taskId);
        if (info == null) return;
        Rect normal = sNormalBounds.get(taskId);
        if (normal != null) {
            sNormalBounds.remove(taskId);
            applyBounds(taskId, normal);
            return;
        }
        Rect current = boundsOf(info);
        if (current == null) return;
        sNormalBounds.put(taskId, new Rect(current));
        Context ctx = sContext;
        int w = ctx != null ? dp(ctx, 220) : 660;
        int h = ctx != null ? dp(ctx, 150) : 450;
        int left = ctx != null ? dp(ctx, 12) : 36;
        int top = ctx != null ? dp(ctx, 96) : 288;
        Rect mini = new Rect(left, top, left + w, top + h);
        mini.offset(Math.max(0, current.left - (ctx != null ? dp(ctx, 24) : 72)),
                Math.max(0, current.top - (ctx != null ? dp(ctx, 24) : 72)));
        applyBounds(taskId, mini);
    }

    static void fullscreenTask(int taskId) {
        Context ctx = sContext;
        Rect screen = ctx != null ? screenBounds(ctx) : null;
        applyWindowMode(taskId, SmallWindowBridge.WINDOWING_MODE_FULLSCREEN, screen);
    }

    static void closeTask(int taskId) {
        try {
            Class<?> cls = Class.forName("android.app.ActivityTaskManager");
            Object atm = cls.getMethod("getService").invoke(null);
            atm.getClass().getMethod("removeTask", int.class).invoke(atm, taskId);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": closeTask failed " + t);
        }
    }

    static boolean applyBounds(int taskId, Rect bounds) {
        Object org = sOrganizer;
        Object info = getInfo(taskId);
        if (org == null || info == null) return false;
        try {
            Object token = XposedHelpers.getObjectField(info, "token");
            Class<?> wctClass = Class.forName("android.window.WindowContainerTransaction");
            Object txn = wctClass.getDeclaredConstructor().newInstance();
            Method setBounds = wctClass.getMethod("setBounds",
                    Class.forName("android.window.WindowContainerToken"), Rect.class);
            setBounds.invoke(txn, token, bounds);
            XposedHelpers.callMethod(org, "applyTransaction", txn);
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": applyBounds failed " + t);
            return false;
        }
    }

    static boolean applyWindowMode(int taskId, int mode, Rect bounds) {
        Object org = sOrganizer;
        Object info = getInfo(taskId);
        if (org == null || info == null) return false;
        try {
            Object token = XposedHelpers.getObjectField(info, "token");
            Class<?> wctClass = Class.forName("android.window.WindowContainerTransaction");
            Object txn = wctClass.getDeclaredConstructor().newInstance();
            Method setMode = wctClass.getMethod("setWindowingMode",
                    Class.forName("android.window.WindowContainerToken"), int.class);
            setMode.invoke(txn, token, mode);
            if (bounds != null) {
                Method setBounds = wctClass.getMethod("setBounds",
                        Class.forName("android.window.WindowContainerToken"), Rect.class);
                setBounds.invoke(txn, token, bounds);
            }
            XposedHelpers.callMethod(org, "applyTransaction", txn);
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": applyWindowMode failed " + t);
            return false;
        }
    }

    private static Object getInfo(int taskId) {
        Object org = sOrganizer;
        if (org == null) return null;
        try {
            return XposedHelpers.callMethod(org, "getRunningTaskInfo", taskId);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Rect boundsOf(Object info) {
        try {
            Object config = XposedHelpers.getObjectField(info, "configuration");
            Object wc = XposedHelpers.getObjectField(config, "windowConfiguration");
            Object b = XposedHelpers.callMethod(wc, "getBounds");
            if (b instanceof Rect) return new Rect((Rect) b);
        } catch (Throwable ignored) {}
        return null;
    }

    private static Rect screenBounds(Context context) {
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            return new Rect(wm.getCurrentWindowMetrics().getBounds());
        } catch (Throwable t) {
            return null;
        }
    }

    private static int intField(Object target, String field, int def) {
        try {
            return XposedHelpers.getIntField(target, field);
        } catch (Throwable t) {
            return def;
        }
    }

    private static int dp(Context context, int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }
}
