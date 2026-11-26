package io.github.wumisc.nativewindow.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.SystemClock;
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
 * - freeform 任务的原生 caption 由
 *   com.android.wm.shell.windowdecor.CaptionWindowDecorViewModel#shouldShowWindowDecor 决定；
 *   freeform 分支直接 return true，所以会画一条 AOSP 标题栏。我们要把它关掉，换成 HyperOS 3 chrome。
 * - 任务状态从 com.android.wm.shell.ShellTaskOrganizer 的 onTaskAppeared / onTaskInfoChanged /
 *   onTaskVanished 来；同一个对象还能 applyTransaction(WindowContainerTransaction)，
 *   这是移动 / 缩放 / 切全屏的系统级入口。
 * - 关任务走 IActivityTaskManager.removeTask(int)，SystemUI 有系统 UID。
 *
 * 设计：App 只做 chrome 的 View；真正的任务边界修改在 SystemUI 进程用 WCT 完成。
 */
public final class SystemUiHook {
    private static final String TAG = "NativeWindow/SUI";
    public static final String MODULE_PKG = "io.github.wumisc.nativewindow";

    public static final String ACTION_TASK_CHANGED =
            "io.github.wumisc.nativewindow.action.TASK_CHANGED";
    public static final String ACTION_TASK_VANISHED =
            "io.github.wumisc.nativewindow.action.TASK_VANISHED";
    public static final String ACTION_MOVE_TASK =
            "io.github.wumisc.nativewindow.action.MOVE_TASK";
    public static final String ACTION_MINI_TASK =
            "io.github.wumisc.nativewindow.action.MINI_TASK";
    public static final String ACTION_FULLSCREEN_TASK =
            "io.github.wumisc.nativewindow.action.FULLSCREEN_TASK";
    public static final String ACTION_CLOSE_TASK =
            "io.github.wumisc.nativewindow.action.CLOSE_TASK";
    public static final String ACTION_QUERY_TASK =
            "io.github.wumisc.nativewindow.action.QUERY_TASK";

    public static final String EXTRA_TASK_ID = "task_id";
    public static final String EXTRA_BOUNDS = "bounds";
    public static final String EXTRA_LABEL = "label";
    public static final String EXTRA_PACKAGE = "package";
    public static final String EXTRA_VISIBLE = "visible";
    public static final String EXTRA_WINDOWING_MODE = "windowing_mode";

    private static volatile Object sOrganizer;
    private static volatile Context sContext;
    private static volatile int sModuleUid = -1;
    private static volatile long sLastPublish;

    private SystemUiHook() {}

    public static void install(XC_LoadPackage.LoadPackageParam lp) {
        hookSystemUiApplication(lp);
        hookTaskOrganizer(lp);
        hookCaption(lp);
    }

    private static void hookSystemUiApplication(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> cls = XposedHelpers.findClassIfExists(
                    "com.android.systemui.application.impl.SystemUIApplicationImpl",
                    lp.classLoader);
            if (cls == null) {
                XposedBridge.log(TAG + ": SystemUIApplicationImpl not found");
                return;
            }
            XposedHelpers.findAndHookMethod(cls, "onCreate", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Context ctx = (Context) param.thisObject;
                    onSystemUiReady(ctx);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook SystemUI app failed " + t);
        }
    }

    private static void onSystemUiReady(Context context) {
        sContext = context;
        try {
            sModuleUid = context.getPackageManager().getPackageUid(MODULE_PKG, 0);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": module uid failed " + t);
        }
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_MOVE_TASK);
            filter.addAction(ACTION_MINI_TASK);
            filter.addAction(ACTION_FULLSCREEN_TASK);
            filter.addAction(ACTION_CLOSE_TASK);
            filter.addAction(ACTION_QUERY_TASK);
            context.registerReceiver(new CommandReceiver(), filter, Context.RECEIVER_EXPORTED);
            XposedBridge.log(TAG + ": command receiver registered");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": register command receiver failed " + t);
        }
    }

    private static void hookTaskOrganizer(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> cls = XposedHelpers.findClassIfExists(
                    "com.android.wm.shell.ShellTaskOrganizer", lp.classLoader);
            if (cls == null) {
                XposedBridge.log(TAG + ": ShellTaskOrganizer not found");
                return;
            }
            XposedBridge.hookAllConstructors(cls, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    sOrganizer = param.thisObject;
                    XposedBridge.log(TAG + ": ShellTaskOrganizer captured");
                }
            });
            XposedBridge.hookAllMethods(cls, "onTaskAppeared", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length >= 1) publish(param.args[0], false);
                }
            });
            XposedBridge.hookAllMethods(cls, "onTaskInfoChanged", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length >= 1) publish(param.args[0], false);
                }
            });
            XposedBridge.hookAllMethods(cls, "onTaskVanished", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length >= 1) publish(param.args[0], true);
                }
            });
            XposedBridge.log(TAG + ": ShellTaskOrganizer hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": task organizer hook failed " + t);
        }
    }

    private static void hookCaption(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> cls = XposedHelpers.findClassIfExists(
                    "com.android.wm.shell.windowdecor.CaptionWindowDecorViewModel",
                    lp.classLoader);
            if (cls == null) return;
            Method target = null;
            for (Method m : cls.getDeclaredMethods()) {
                if ("shouldShowWindowDecor".equals(m.getName())
                        && m.getParameterTypes().length == 1) {
                    target = m;
                    break;
                }
            }
            if (target == null) return;
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length < 1) return;
                    if (windowMode(param.args[0]) == SmallWindowBridge.WINDOWING_MODE_FREEFORM) {
                        param.setResult(false);
                    }
                }
            });
            XposedBridge.log(TAG + ": native freeform caption suppressed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": caption hook failed " + t);
        }
    }

    /** 把 freeform 任务的 bounds / 标题 / 可见性发给模块 App 的 chrome 服务。 */
    private static void publish(Object taskInfo, boolean vanished) {
        Context ctx = sContext;
        if (ctx == null || taskInfo == null) return;
        int taskId = intField(taskInfo, "taskId", -1);
        if (taskId <= 0) return;

        if (vanished) {
            Intent i = new Intent(ACTION_TASK_VANISHED);
            i.setPackage(MODULE_PKG);
            i.putExtra(EXTRA_TASK_ID, taskId);
            ctx.sendBroadcast(i);
            return;
        }

        long now = SystemClock.uptimeMillis();
        if (now - sLastPublish < 12) return;
        sLastPublish = now;

        try {
            Rect bounds = taskBounds(taskInfo);
            if (bounds == null) return;
            int mode = windowMode(taskInfo);
            boolean visible = boolField(taskInfo, "isVisible", true);
            String label = "";
            try {
                Object desc = XposedHelpers.getObjectField(taskInfo, "taskDescription");
                Object l = XposedHelpers.callMethod(desc, "getLabel");
                if (l != null) label = String.valueOf(l);
            } catch (Throwable ignored) {}
            String pkg = "";
            try {
                Object base = XposedHelpers.getObjectField(taskInfo, "baseActivity");
                Object p = XposedHelpers.callMethod(base, "getPackageName");
                if (p != null) pkg = String.valueOf(p);
            } catch (Throwable ignored) {}

            Intent i = new Intent(ACTION_TASK_CHANGED);
            i.setPackage(MODULE_PKG);
            i.putExtra(EXTRA_TASK_ID, taskId);
            i.putExtra(EXTRA_BOUNDS, bounds);
            i.putExtra(EXTRA_LABEL, label);
            i.putExtra(EXTRA_PACKAGE, pkg);
            i.putExtra(EXTRA_VISIBLE, visible);
            i.putExtra(EXTRA_WINDOWING_MODE, mode);
            ctx.sendBroadcast(i);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": publish failed " + t);
        }
    }


    /** TaskInfo 没有 public bounds 字段；边界在 configuration.windowConfiguration.getBounds()。 */
    private static Rect taskBounds(Object taskInfo) {
        try {
            Object config = XposedHelpers.getObjectField(taskInfo, "configuration");
            Object wc = XposedHelpers.getObjectField(config, "windowConfiguration");
            Object b = XposedHelpers.callMethod(wc, "getBounds");
            if (b instanceof Rect) return new Rect((Rect) b);
        } catch (Throwable ignored) {}
        return null;
    }

    private static int windowMode(Object info) {
        try {
            Object mode = XposedHelpers.callMethod(info, "getWindowingMode");
            if (mode instanceof Integer) return (Integer) mode;
        } catch (Throwable ignored) {}
        try {
            Object config = XposedHelpers.getObjectField(info, "windowConfiguration");
            Object mode = XposedHelpers.callMethod(config, "getWindowingMode");
            if (mode instanceof Integer) return (Integer) mode;
        } catch (Throwable ignored) {}
        // 最后兜底：TaskInfo.isFreeform() 在 Android 17 仍然存在。
        try {
            Object free = XposedHelpers.callMethod(info, "isFreeform");
            if (free instanceof Boolean && (Boolean) free) {
                return SmallWindowBridge.WINDOWING_MODE_FREEFORM;
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static int intField(Object target, String field, int def) {
        try {
            return XposedHelpers.getIntField(target, field);
        } catch (Throwable t) {
            return def;
        }
    }

    private static boolean boolField(Object target, String field, boolean def) {
        try {
            return XposedHelpers.getBooleanField(target, field);
        } catch (Throwable t) {
            return def;
        }
    }

    /** App chrome 发来的命令；真正的 WCT 在这里执行。 */
    private static final class CommandReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            if (!allowSender(this)) {
                XposedBridge.log(TAG + ": command rejected from uid=" + Binder.getCallingUid());
                return;
            }
            int taskId = intent.getIntExtra(EXTRA_TASK_ID, -1);
            if (taskId <= 0) return;
            String action = intent.getAction();
            if (ACTION_CLOSE_TASK.equals(action)) {
                closeTask(taskId);
                return;
            }
            if (ACTION_QUERY_TASK.equals(action)) {
                Object info = getInfo(taskId);
                if (info != null) publish(info, false);
                return;
            }
            if (ACTION_FULLSCREEN_TASK.equals(action)) {
                Object info = getInfo(taskId);
                Rect screen = screenBounds(context);
                applyWindowMode(taskId, SmallWindowBridge.WINDOWING_MODE_FULLSCREEN, screen);
                return;
            }
            if (ACTION_MINI_TASK.equals(action)) {
                Object info = getInfo(taskId);
                if (info == null) return;
                Rect current = boundsOf(info);
                int w = dp(context, 220);
                int h = dp(context, 150);
                int left = dp(context, 12);
                int top = dp(context, 96);
                Rect mini = new Rect(left, top, left + w, top + h);
                if (current != null && current.left > 0) {
                    // 从当前窗口位置往左上收，观感更接近 HyperOS 的贴边迷你窗。
                    mini.offset(Math.max(0, current.left - dp(context, 24)),
                            Math.max(0, current.top - dp(context, 24)));
                }
                applyBounds(taskId, mini);
                return;
            }
            Rect bounds = intent.getParcelableExtra(EXTRA_BOUNDS);
            if (bounds != null) applyBounds(taskId, bounds);
        }
    }

    private static boolean allowSender(BroadcastReceiver receiver) {
        try {
            Object uid = XposedHelpers.callMethod(receiver, "getSendingUid");
            if (uid instanceof Integer) {
                int u = (Integer) uid;
                return u == sModuleUid || u == 1000;
            }
        } catch (Throwable ignored) {}
        // API 未暴露 getSendingUid 时 fail-open；alpha 版接受，正式版换签名 Binder。
        return true;
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
            Object b = XposedHelpers.getObjectField(info, "bounds");
            if (b instanceof Rect) return new Rect((Rect) b);
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean applyBounds(int taskId, Rect bounds) {
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

    private static boolean applyWindowMode(int taskId, int mode, Rect bounds) {
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

    private static void closeTask(int taskId) {
        try {
            Class<?> cls = Class.forName("android.app.ActivityTaskManager");
            Object atm = cls.getMethod("getService").invoke(null);
            atm.getClass().getMethod("removeTask", int.class).invoke(atm, taskId);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": closeTask failed " + t);
        }
    }

    private static Rect screenBounds(Context context) {
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            return new Rect(wm.getCurrentWindowMetrics().getBounds());
        } catch (Throwable t) {
            return null;
        }
    }

    private static int dp(Context context, int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }
}
