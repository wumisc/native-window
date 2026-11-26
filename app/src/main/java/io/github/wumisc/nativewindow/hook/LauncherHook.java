package io.github.wumisc.nativewindow.hook;

import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 最近任务 / 桌面入口注入。
 *
 * 源码核对：
 * - LOS 17（lineage-24.0）与 PixelOS 17 都是 AOSP 17 的 Launcher3/Quickstep 骨架。
 * - Pixel Launcher 是预编译 NexusLauncherRelease，但 com.android.quickstep.TaskOverlayFactory、
 *   com.android.launcher3.popup.RemoteActionShortcut 这两个类名在 CP3A.260905.009 反混淆后仍保留
 *   （见 PixelLauncherEvolved 的 DEX 审计），所以可以按类名 Hook，不需要 DexKit 才能起步。
 * - Launcher 本身持有 START_TASKS_FROM_RECENTS / MANAGE_ACTIVITY_TASKS，因此“小窗”动作要在
 *   Launcher 进程里执行，而不是把 PendingIntent 发回模块 App。
 *
 * 交互链路：
 *   TaskOverlayFactory 菜单 → RemoteActionShortcut → PendingIntent →
 *   Launcher 进程的 TaskLaunchReceiver → SmallWindowBridge.startActivityFromRecents(Bundle)
 */
public final class LauncherHook {
    private static final String TAG = "NativeWindow/Launcher";
    public static final String MODULE_PKG = "io.github.wumisc.nativewindow";
    public static final String ACTION_OPEN_SMALL_WINDOW =
            "io.github.wumisc.nativewindow.action.OPEN_SMALL_WINDOW";
    public static final String EXTRA_TASK_ID = "task_id";
    public static final String EXTRA_BOUNDS = "bounds";

    private static volatile boolean sReceiverRegistered;

    private LauncherHook() {}

    public static void install(XC_LoadPackage.LoadPackageParam lp) {
        hookLauncherCreate(lp);
        hookTaskOverlay(lp);
    }

    /** 在 Launcher 进程注册接收器；PendingIntent 由 Launcher 自己创建，身份是 Launcher UID。 */
    private static void hookLauncherCreate(XC_LoadPackage.LoadPackageParam lp) {
        hookOnCreate(lp.classLoader, "com.android.launcher3.Launcher");
        hookOnCreate(lp.classLoader, "com.android.launcher3.uioverrides.QuickstepLauncher");
    }

    private static void hookOnCreate(ClassLoader cl, String className) {
        try {
            Class<?> cls = XposedHelpers.findClassIfExists(className, cl);
            if (cls == null) return;
            XposedBridge.hookAllMethods(cls, "onCreate", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length != 1) return;
                    if (!(param.args[0] instanceof Bundle)) return;
                    ensureReceiver((Context) param.thisObject);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook onCreate " + className + " failed " + t);
        }
    }

    private static synchronized void ensureReceiver(Context context) {
        if (sReceiverRegistered || context == null) return;
        try {
            IntentFilter filter = new IntentFilter(ACTION_OPEN_SMALL_WINDOW);
            context.registerReceiver(new TaskLaunchReceiver(), filter,
                    Context.RECEIVER_NOT_EXPORTED);
            sReceiverRegistered = true;
            XposedBridge.log(TAG + ": TaskLaunchReceiver registered in "
                    + context.getPackageName());
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": register receiver failed " + t);
        }
    }

    private static void hookTaskOverlay(XC_LoadPackage.LoadPackageParam lp) {
        Class<?> factory = XposedHelpers.findClassIfExists(
                "com.android.quickstep.TaskOverlayFactory", lp.classLoader);
        if (factory == null) {
            XposedBridge.log(TAG + ": TaskOverlayFactory not found in " + lp.packageName);
            return;
        }
        XposedBridge.hookAllMethods(factory, "getEnabledShortcuts", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.getResult() instanceof List)) return;
                if (param.args.length < 1 || !(param.args[0] instanceof View)) return;
                try {
                    List shortcuts = (List) param.getResult();
                    if (containsSmallWindow(shortcuts)) return;
                    View taskView = (View) param.args[0];
                    Object shortcut = buildShortcut(taskView);
                    if (shortcut == null) return;
                    try {
                        shortcuts.add(shortcut);
                    } catch (Throwable immutable) {
                        // 某些 ROM 返回不可变 List；复制一份再塞回去。
                        ArrayList copy = new ArrayList(shortcuts);
                        copy.add(shortcut);
                        param.setResult(copy);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": add shortcut failed " + t);
                }
            }
        });
        XposedBridge.log(TAG + ": recents hook installed ("
                + LauncherPackages.displayName(lp.packageName) + ")");
    }

    private static boolean containsSmallWindow(List shortcuts) {
        for (Object o : shortcuts) {
            if (o == null) continue;
            if (o.getClass().getName().contains("RemoteActionShortcut")) {
                try {
                    Object action = XposedHelpers.getObjectField(o, "mAction");
                    Object title = XposedHelpers.callMethod(action, "getTitle");
                    if (title != null && String.valueOf(title).contains("小窗")) return true;
                } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    private static Object buildShortcut(View taskView) {
        Context context = taskView.getContext();
        int taskId = readTaskId(taskView);
        if (taskId <= 0) return null;
        Object itemInfo = readItemInfo(taskView);
        if (itemInfo == null) return null;

        // 显式发给 Launcher 自己：动态接收器由 LauncherHook 注册，发送身份是 Launcher UID。
        Intent intent = new Intent(ACTION_OPEN_SMALL_WINDOW);
        intent.setPackage(context.getPackageName());
        intent.putExtra(EXTRA_TASK_ID, taskId);
        intent.putExtra(EXTRA_BOUNDS, taskBounds(taskView));
        PendingIntent pending = PendingIntent.getBroadcast(
                context, taskId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Icon icon = icon(context);
        RemoteAction action = new RemoteAction(icon, "小窗", "以自由窗口打开", pending);
        Class<?> shortcutClass = XposedHelpers.findClassIfExists(
                "com.android.launcher3.popup.RemoteActionShortcut",
                taskView.getClass().getClassLoader());
        if (shortcutClass == null) {
            XposedBridge.log(TAG + ": RemoteActionShortcut not found");
            return null;
        }
        for (Constructor<?> ctor : shortcutClass.getDeclaredConstructors()) {
            Class<?>[] p = ctor.getParameterTypes();
            if (p.length != 4) continue;
            if (!p[0].getName().equals("android.app.RemoteAction")) continue;
            if (!View.class.isAssignableFrom(p[3])) continue;
            try {
                ctor.setAccessible(true);
                return ctor.newInstance(action, context, itemInfo, taskView);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": ctor failed " + t);
            }
        }
        return null;
    }

    private static android.graphics.Rect taskBounds(View taskView) {
        try {
            int[] pos = new int[2];
            taskView.getLocationOnScreen(pos);
            int w = (int) (taskView.getWidth() * taskView.getScaleX());
            int h = (int) (taskView.getHeight() * taskView.getScaleY());
            return new android.graphics.Rect(pos[0], pos[1], pos[0] + w, pos[1] + h);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int readTaskId(View taskView) {
        try {
            Object ids = XposedHelpers.callMethod(taskView, "getTaskIds");
            if (ids instanceof int[] && ((int[]) ids).length > 0) return ((int[]) ids)[0];
        } catch (Throwable ignored) {}
        try {
            Object ids = XposedHelpers.getObjectField(taskView, "taskIds");
            if (ids instanceof int[] && ((int[]) ids).length > 0) return ((int[]) ids)[0];
        } catch (Throwable ignored) {}
        return -1;
    }

    private static Object readItemInfo(View taskView) {
        try {
            Object info = XposedHelpers.callMethod(taskView, "getItemInfo");
            if (info != null) return info;
        } catch (Throwable ignored) {}
        try {
            return XposedHelpers.getObjectField(taskView, "itemInfo");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Icon icon(Context context) {
        try {
            Drawable d = context.getDrawable(android.R.drawable.ic_menu_manage);
            Bitmap bmp;
            if (d instanceof BitmapDrawable) {
                bmp = ((BitmapDrawable) d).getBitmap();
            } else {
                bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp);
                d.setBounds(0, 0, 48, 48);
                d.draw(c);
            }
            return Icon.createWithBitmap(bmp);
        } catch (Throwable t) {
            return Icon.createWithBitmap(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888));
        }
    }

    /** 运行在 Launcher 进程，所以能用 Launcher 的 START_TASKS_FROM_RECENTS。 */
    private static final class TaskLaunchReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_OPEN_SMALL_WINDOW.equals(intent.getAction())) return;
            int taskId = intent.getIntExtra(EXTRA_TASK_ID, -1);
            if (taskId <= 0) return;
            android.graphics.Rect bounds = intent.getParcelableExtra(EXTRA_BOUNDS);
            boolean ok = bounds != null
                    ? SmallWindowBridge.openFromTask(context, taskId, bounds)
                    : SmallWindowBridge.openFromTask(context, taskId);
            if (!ok) {
                Toast.makeText(context, "小窗启动失败，请查看 LSPosed 日志",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            // chrome 由 SystemUI 的原生 CaptionWindowDecoration 接管，不再拉起 App 悬浮窗。
        }
    }
}
