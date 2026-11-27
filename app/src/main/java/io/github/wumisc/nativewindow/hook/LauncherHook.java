package io.github.wumisc.nativewindow.hook;

import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
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

import io.github.wumisc.nativewindow.smallwindow.VirtualWindowClient;

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
    private static final String ACTION_FORCE_SMALL_SYSTEMUI =
            "io.github.wumisc.nativewindow.action.FORCE_SMALL_WINDOW";

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
        String[] factoryCandidates = {
                "com.android.quickstep.TaskOverlayFactory",
                "com.android.launcher3.quickstep.TaskOverlayFactory"
        };
        Class<?> factory = null;
        String hookedFactory = null;
        for (String name : factoryCandidates) {
            factory = XposedHelpers.findClassIfExists(name, lp.classLoader);
            if (factory != null) {
                hookedFactory = name;
                break;
            }
        }
        if (factory == null) {
            XposedBridge.log(TAG + ": TaskOverlayFactory not found in " + lp.packageName);
            return;
        }
        final Class<?> overlayFactory = factory;
        final String overlayFactoryName = hookedFactory;
        XposedBridge.hookAllMethods(overlayFactory, "getEnabledShortcuts", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.getResult() instanceof List)) return;
                if (param.args.length < 1 || !(param.args[0] instanceof View)) return;
                try {
                    List shortcuts = (List) param.getResult();
                    if (shortcuts.isEmpty()) return;
                    if (containsSmallWindow(shortcuts)) return;
                    View taskView = (View) param.args[0];
                    // TaskOverlayFactory 可能在没有 Launcher.onCreate 的情况下被拉起（进程被回收后
                    // 直接从最近任务进入）。接收器必须在这里也保证注册，否则菜单点了没反应。
                    ensureReceiver(taskView.getContext());
                    Object shortcut = buildShortcut(taskView, shortcuts);
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
                + LauncherPackages.displayName(lp.packageName) + " factory=" + overlayFactoryName + ")");
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

    private static Object buildShortcut(View taskView, List shortcuts) {
        Context context = taskView.getContext();
        try {
            Object task = XposedHelpers.callMethod(taskView, "getTask");
            Object key = XposedHelpers.getObjectField(task, "key");
            int taskId = XposedHelpers.getIntField(key, "id");
            int userId = XposedHelpers.getIntField(key, "userId");
            if (taskId <= 0) return null;

            Object itemInfo = XposedHelpers.getObjectField(shortcuts.get(0), "mItemInfo");
            // 复制一份，避免菜单复用同一个 ItemInfo 时被改坏。
            try {
                Class<?> ic = itemInfo.getClass();
                Constructor<?> c = ic.getDeclaredConstructor(ic);
                c.setAccessible(true);
                itemInfo = c.newInstance(itemInfo);
            } catch (Throwable ignored) {}
            ComponentName top = (ComponentName) XposedHelpers.callMethod(
                    itemInfo, "getTargetComponent");

            Intent intent = new Intent(VirtualWindowClient.ACTION_OPEN);
            intent.setPackage("android");
            intent.putExtra(VirtualWindowClient.EXTRA_TASK_ID, taskId);
            intent.putExtra(VirtualWindowClient.EXTRA_COMPONENT, top);
            intent.putExtra(VirtualWindowClient.EXTRA_USER_ID, userId);
            intent.putExtra(VirtualWindowClient.EXTRA_SOURCE, "recents");
            PendingIntent pending = PendingIntent.getBroadcast(
                    context, taskId + 1000, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            RemoteAction action = new RemoteAction(icon(context), "小窗", "在悬浮卡片里打开", pending);
            Class<?> shortcutClass = null;
            String[] shortcutCandidates = {
                    "com.android.launcher3.popup.RemoteActionShortcut",
                    "com.android.quickstep.popup.RemoteActionShortcut"
            };
            for (String name : shortcutCandidates) {
                shortcutClass = XposedHelpers.findClassIfExists(name,
                        taskView.getClass().getClassLoader());
                if (shortcutClass != null) break;
            }
            if (shortcutClass == null) return null;
            for (Constructor<?> ctor : shortcutClass.getDeclaredConstructors()) {
                Class<?>[] p = ctor.getParameterTypes();
                if (p.length != 3 && p.length != 4) continue;
                if (!p[0].getName().equals("android.app.RemoteAction")) continue;
                try {
                    ctor.setAccessible(true);
                    if (p.length == 4) {
                        return ctor.newInstance(action, context, itemInfo, null);
                    }
                    return ctor.newInstance(action, context, itemInfo);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": build shortcut failed " + t);
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

    /** 兼容旧路径的接收器；新入口直接把 PendingIntent 发给 system_server。 */
    private static final class TaskLaunchReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_OPEN_SMALL_WINDOW.equals(intent.getAction())) return;
            int taskId = intent.getIntExtra(EXTRA_TASK_ID, -1);
            if (taskId <= 0) return;
            ComponentName component = intent.getParcelableExtra("component");
            boolean ok = VirtualWindowClient.openTask(context, taskId, component, 0);
            Toast.makeText(context, ok ? "正在打开小窗" : "小窗启动失败",
                    Toast.LENGTH_SHORT).show();
        }
    }
}
