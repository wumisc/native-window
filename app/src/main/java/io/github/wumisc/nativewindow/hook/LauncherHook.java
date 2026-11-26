package io.github.wumisc.nativewindow.hook;

import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.UserHandle;
import android.view.View;

import java.lang.reflect.Constructor;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 最近任务 / 桌面入口注入。
 *
 * LOS：com.android.quickstep.TaskOverlayFactory#getEnabledShortcuts
 * PixelOS：同一套 Launcher3 quickstep 类，但包名是 Pixel Launcher；类被混淆时 fail-open。
 */
public final class LauncherHook {
    private static final String TAG = "NativeWindow/Launcher";
    public static final String MODULE_PKG = "io.github.wumisc.nativewindow";

    private LauncherHook() {}

    public static void install(XC_LoadPackage.LoadPackageParam lp) {
        hookTaskOverlay(lp);
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
                    if (shortcut != null) shortcuts.add(shortcut);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": add shortcut failed " + t);
                }
            }
        });
        XposedBridge.log(TAG + ": recents hook installed (" + LauncherPackages.displayName(lp.packageName) + ")");
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

        Intent intent = new Intent(OpenReceiver.ACTION_OPEN_SMALL_WINDOW);
        intent.setComponent(new ComponentName(MODULE_PKG, OpenReceiver.class.getName()));
        intent.putExtra(OpenReceiver.EXTRA_TASK_ID, taskId);
        PendingIntent pending = PendingIntent.getBroadcast(
                context, taskId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Icon icon = icon(context);
        RemoteAction action = new RemoteAction(icon, "小窗", "在小窗中打开", pending);
        Class<?> shortcutClass = XposedHelpers.findClassIfExists(
                "com.android.launcher3.popup.RemoteActionShortcut", taskView.getClass().getClassLoader());
        if (shortcutClass == null) return null;
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
}
