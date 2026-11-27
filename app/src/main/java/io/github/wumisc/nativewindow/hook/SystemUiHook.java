package io.github.wumisc.nativewindow.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
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
 * 0.4.1 不再使用 App 悬浮窗 chrome：直接把胶囊塞进原生 caption 的 View 树，
 * 拖动/缩放继续交给 CaptionTouchEventListener，避免“胶囊先动、窗口后动”。
 */
public final class SystemUiHook {
    private static final String TAG = "NativeWindow/SUI";
    private static final String ACTION_FORCE_SMALL =
            "io.github.wumisc.nativewindow.action.FORCE_SMALL_WINDOW";
    private static final String ACTION_ATTACH_CHROME =
            "io.github.wumisc.nativewindow.action.ATTACH_CHROME";
    private static final String ACTION_SELF_TEST_ACK =
            "io.github.wumisc.nativewindow.action.SELF_TEST_ACK";
    private static final String EXTRA_TASK_ID = "task_id";
    private static final String EXTRA_BOUNDS = "bounds";
    private static final String EXTRA_MODE = "mode";
    private static final String EXTRA_SELF_TEST = "self_test";
    private static final String MODULE_PKG = "io.github.wumisc.nativewindow";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean sRequestReceiverRegistered;

    private static volatile Object sOrganizer;
    private static volatile Context sContext;
    private static volatile int sModuleUid = -1;
    private static final android.util.SparseArray<Rect> sNormalBounds =
            new android.util.SparseArray<>();

    private SystemUiHook() {}

    public static void install(XC_LoadPackage.LoadPackageParam lp) {
        HyperOsSystemUiHook.install(lp);
        hookSystemUiApplication(lp);
        hookTaskOrganizer(lp);
        hookWindowDecorInflation(lp);
        hookCaptionColor(lp);
    }

    private static void hookSystemUiApplication(XC_LoadPackage.LoadPackageParam lp) {
        // AOSP 15/17 的 SystemUI Application 类名在版本之间变过：
        // - AOSP 15：com.android.systemui.SystemUIApplication
        // - 部分 AOSP 17 构建：com.android.systemui.application.impl.SystemUIApplicationImpl
        // 两个都挂，谁存在谁生效；不能只猜一个，否则 receiver 根本不注册。
        String[] candidates = {
                "com.android.systemui.SystemUIApplication",
                "com.android.systemui.application.impl.SystemUIApplicationImpl"
        };
        int hooked = 0;
        for (String name : candidates) {
            try {
                Class<?> cls = XposedHelpers.findClassIfExists(name, lp.classLoader);
                if (cls == null) continue;
                XposedHelpers.findAndHookMethod(cls, "onCreate", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Context context = (Context) param.thisObject;
                        sContext = context;
                        registerRequestReceiver(context);
                    }
                });
                hooked++;
                XposedBridge.log(TAG + ": application hooked " + name);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": hook " + name + " failed " + t);
            }
        }
        if (hooked == 0) {
            XposedBridge.log(TAG + ": no SystemUI application class found; "
                    + "fallback to ShellTaskOrganizer context");
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
                    // 兜底：有些 SystemUI 版本 Application#onCreate 找不到，但
                    // ShellTaskOrganizer 构造参数一定带 Context，用这个注册 receiver。
                    if (sContext == null && param.args != null) {
                        for (Object arg : param.args) {
                            if (arg instanceof Context) {
                                sContext = (Context) arg;
                                registerRequestReceiver(sContext);
                                break;
                            }
                        }
                    }
                }
            });
            XposedBridge.hookAllMethods(cls, "onTaskVanished", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length >= 1) {
                        int vanished = intField(param.args[0], "taskId", -1);
                        sNormalBounds.remove(vanished);
                        NativeChromeOverlay.hideTask(vanished);
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

    /**
     * CaptionWindowDecoration#setupCaptionColor 会把 caption 的 GradientDrawable 刷成
     * statusBarColor；必须在它之后再把颜色刷透明，否则胶囊下面还是 AOSP 深色标题栏。
     */
    private static void hookCaptionColor(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> cls = XposedHelpers.findClassIfExists(
                    "com.android.wm.shell.windowdecor.CaptionWindowDecoration", lp.classLoader);
            if (cls == null) return;
            XC_MethodHook clear = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    NativeCaptionDecorator.clearCaptionBackground(param.thisObject, null);
                }
            };
            XposedBridge.hookAllMethods(cls, "setupCaptionColor", clear);
            XposedBridge.hookAllMethods(cls, "setCaptionColor", clear);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": caption color hook failed " + t);
        }
    }

    /**
     * Launcher 进程只能 startActivityFromRecents；Android 17 的该接口对“已在最近任务里的 task”
     * 只做 moveTaskToFront，ActivityOptions 里的 launchBounds/windowingMode 不会重新套到已有 task。
     * 因此 Launcher 发一条显式广播给 SystemUI，由这里用 WCT 真正把 task 改成 freeform + bounds。
     *
     * 接收器 exported，但校验 sender UID 必须是 Launcher，避免任意 App 改任务。
     */
    private static synchronized void registerRequestReceiver(Context context) {
        if (sRequestReceiverRegistered || context == null) return;
        try {
            IntentFilter filter = new IntentFilter(ACTION_FORCE_SMALL);
            filter.addAction(ACTION_ATTACH_CHROME);
            context.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent intent) {
                    if (intent == null) return;
                    if (ACTION_ATTACH_CHROME.equals(intent.getAction())) {
                        onAttachChrome(ctx, intent, getSentFromUid());
                        return;
                    }
                    if (!ACTION_FORCE_SMALL.equals(intent.getAction())) return;
                    int sender = getSentFromUid();
                    int taskId = intent.getIntExtra(EXTRA_TASK_ID, -1);
                    boolean launcher = isLauncherUid(ctx, sender);
                    // Android 15 的 BroadcastReceiver#getSentFromUid() 对普通 App 广播返回 -1，
                    // 不能只看 UID。这里用“task 的 top activity 是不是模块自己”兜底：
                    // 即使 sender=-1，也只能改模块自己的 task，不能改别的 App。
                    boolean moduleTask = isModuleTask(taskId);
                    boolean selfTest = intent.getBooleanExtra(EXTRA_SELF_TEST, false)
                            && (isModuleUid(ctx, sender) || moduleTask);
                    XposedBridge.log(TAG + ": force-small received task=" + taskId
                            + " sender=" + sender + " launcher=" + launcher
                            + " moduleTask=" + moduleTask + " selfTest=" + selfTest);
                    if (!launcher && !selfTest) return;
                    if (taskId <= 0) return;
                    int mode = intent.getIntExtra(EXTRA_MODE,
                            SmallWindowBridge.WINDOWING_MODE_FREEFORM);
                    if (mode != SmallWindowBridge.WINDOWING_MODE_FREEFORM
                            && mode != SmallWindowBridge.WINDOWING_MODE_FULLSCREEN) return;
                    Rect bounds = intent.getParcelableExtra(EXTRA_BOUNDS);
                    if (selfTest) {
                        sendAck("received", taskId, mode, true,
                                "SystemUI receiver registered");
                    }
                    forceWindowWithRetry(taskId, mode, bounds, 0, selfTest);
                }
            }, filter, Context.RECEIVER_EXPORTED);
            sRequestReceiverRegistered = true;
            XposedBridge.log(TAG + ": force-small receiver registered");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": force-small receiver failed " + t);
        }
    }

    private static void onAttachChrome(Context ctx, Intent intent, int sender) {
        boolean module = isModuleUid(ctx, sender);
        boolean launcher = isLauncherUid(ctx, sender);
        XposedBridge.log(TAG + ": attach chrome sender=" + sender
                + " module=" + module + " launcher=" + launcher);
        if (!module && !launcher && sender != android.os.Process.SYSTEM_UID) return;
        String target = intent.getStringExtra("target_pkg");
        Rect bounds = intent.getParcelableExtra(EXTRA_BOUNDS);
        resolveChrome(target, bounds, 0);
    }

    /** 启动和 top task 切换有几帧延迟，最多重试 4 次，只认目标包名的 task。 */
    private static void resolveChrome(String target, Rect bounds, int attempt) {
        int taskId = topTaskId();
        Object info = taskId > 0 ? getInfo(taskId) : null;
        String top = topPackage(info);
        if (info == null || (target != null && !target.equals(top))) {
            if (attempt < 3) {
                MAIN.postDelayed(() -> resolveChrome(target, bounds, attempt + 1), 350);
            } else {
                XposedBridge.log(TAG + ": attach chrome target not found target="
                        + target + " top=" + top + " task=" + taskId);
            }
            return;
        }
        Rect b = bounds != null ? bounds : boundsOf(info);
        if (b == null) return;
        // 目标 App 可能已经有一个全屏 task，startActivity 只会把它移到前台，
        // ActivityOptions 里的 freeform 不会重新套用；必须在这里对真实 task 再下一次 WCT。
        forceWindowWithRetry(taskId, SmallWindowBridge.WINDOWING_MODE_FREEFORM, b, 0, false);
        XposedBridge.log(TAG + ": attach chrome task=" + taskId + " pkg=" + top
                + " bounds=" + b);
    }

    private static int topTaskId() {
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object atm = atmCls.getMethod("getService").invoke(null);
            for (java.lang.reflect.Method m : atmCls.getMethods()) {
                if (!"getTasks".equals(m.getName())) continue;
                Object result;
                if (m.getParameterTypes().length == 2) {
                    result = m.invoke(atm, 1, false);
                } else if (m.getParameterTypes().length == 3) {
                    result = m.invoke(atm, 1, false, false);
                } else if (m.getParameterTypes().length == 1) {
                    result = m.invoke(atm, 1);
                } else {
                    continue;
                }
                if (result instanceof java.util.List && !((java.util.List) result).isEmpty()) {
                    Object task = ((java.util.List) result).get(0);
                    return intField(task, "taskId", -1);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": topTaskId failed " + t);
        }
        return -1;
    }

    private static String topPackage(Object info) {
        if (info == null) return null;
        try {
            Object top = XposedHelpers.getObjectField(info, "topActivity");
            if (top instanceof android.content.ComponentName) {
                return ((android.content.ComponentName) top).getPackageName();
            }
        } catch (Throwable ignored) {}
        try {
            Object topInfo = XposedHelpers.getObjectField(info, "topActivityInfo");
            Object pkg = XposedHelpers.getObjectField(topInfo, "packageName");
            return pkg != null ? String.valueOf(pkg) : null;
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean isLauncherUid(Context context, int uid) {        PackageManager pm = context.getPackageManager();
        try {
            if (uid == pm.getPackageUid("com.android.launcher3", 0)) return true;
        } catch (Throwable ignored) {}
        try {
            if (uid == pm.getPackageUid("com.google.android.apps.nexuslauncher", 0)) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean isModuleUid(Context context, int uid) {
        if (uid < 0) return false;
        int cached = sModuleUid;
        if (cached <= 0) {
            try {
                cached = context.getPackageManager().getPackageUid(MODULE_PKG, 0);
                sModuleUid = cached;
            } catch (Throwable ignored) {
                return false;
            }
        }
        return uid == cached;
    }

    /** 只允许改 top activity 属于模块自己的 task；用于 self_test 兜底。 */
    private static boolean isModuleTask(int taskId) {
        if (taskId <= 0) return false;
        Object info = getInfo(taskId);
        if (info == null) return false;
        try {
            Object top = XposedHelpers.getObjectField(info, "topActivity");
            if (top instanceof android.content.ComponentName) {
                return MODULE_PKG.equals(((android.content.ComponentName) top).getPackageName());
            }
        } catch (Throwable ignored) {}
        try {
            Object topInfo = XposedHelpers.getObjectField(info, "topActivityInfo");
            Object pkg = XposedHelpers.getObjectField(topInfo, "packageName");
            return MODULE_PKG.equals(pkg);
        } catch (Throwable ignored) {}
        return false;
    }

    private static void forceWindowWithRetry(int taskId, int mode, Rect bounds,
            int attempt, boolean selfTest) {
        Object info = getInfo(taskId);
        if (info != null) {
            if (applyWindowMode(taskId, mode, bounds)) {
                if (mode == SmallWindowBridge.WINDOWING_MODE_FREEFORM) {
                    NativeChromeOverlay.showForTask(taskId, boundsOf(info));
                } else {
                    NativeChromeOverlay.hideTask(taskId);
                }
                if (selfTest) {
                    sendAck("applied", taskId, mode, true,
                            "WCT setWindowingMode(" + mode + ")"
                                    + (bounds == null ? "" : " + setBounds"));
                }
                return;
            }
        }
        if (attempt >= 5) {
            if (selfTest) {
                sendAck("applied", taskId, mode, false,
                        info == null ? "getRunningTaskInfo returned null"
                                : "WCT applyTransaction failed");
            }
            return;
        }
        MAIN.postDelayed(() -> forceWindowWithRetry(taskId, mode, bounds, attempt + 1,
                selfTest), 80);
    }

    /** 自检专用回执；只在 self_test=true 时发送，普通开窗不会暴露内部状态。 */
    private static void sendAck(String stage, int taskId, int mode, boolean ok, String detail) {
        Context ctx = sContext;
        if (ctx == null) return;
        try {
            Intent ack = new Intent(ACTION_SELF_TEST_ACK);
            ack.setPackage(MODULE_PKG);
            ack.putExtra("stage", stage);
            ack.putExtra("task_id", taskId);
            ack.putExtra("mode", mode);
            ack.putExtra("ok", ok);
            ack.putExtra("detail", detail);
            ctx.sendBroadcast(ack);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": send self-test ack failed " + t);
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
        NativeChromeOverlay.sync(taskId, mini);
    }

    static void fullscreenTask(int taskId) {
        NativeChromeOverlay.hideTask(taskId);
        Context ctx = sContext;
        Rect screen = ctx != null ? screenBounds(ctx) : null;
        applyWindowMode(taskId, SmallWindowBridge.WINDOWING_MODE_FULLSCREEN, screen);
    }

    static void closeTask(int taskId) {
        NativeChromeOverlay.hideTask(taskId);
        try {
            Class<?> cls = Class.forName("android.app.ActivityTaskManager");
            Object atm = cls.getMethod("getService").invoke(null);
            atm.getClass().getMethod("removeTask", int.class).invoke(atm, taskId);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": closeTask failed " + t);
        }
    }

    static Context context() {
        return sContext;
    }

    static Object info(int taskId) {
        return getInfo(taskId);
    }

    static Rect taskBounds(int taskId) {
        return boundsOf(getInfo(taskId));
    }

    static Rect screen() {
        Context ctx = sContext;
        return ctx != null ? screenBounds(ctx) : null;
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
            NativeChromeOverlay.sync(taskId, bounds);
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
            if (mode == SmallWindowBridge.WINDOWING_MODE_FREEFORM) {
                NativeChromeOverlay.sync(taskId, bounds);
            }
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
