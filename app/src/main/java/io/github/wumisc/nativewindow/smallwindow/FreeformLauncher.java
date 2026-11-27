package io.github.wumisc.nativewindow.smallwindow;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;

import java.lang.reflect.Method;

/**
 * 从模块 App 直接拉起任意应用的小窗。
 *
 * 双保险：
 * 1. 优先用 HyperOS 自己的 MiuiMultiWindowUtils.getActivityOptions(context, pkg, true, false)；
 * 2. Intent 里带 FREEFORM 标记，system_server 的 ActivityStarterInjector / ATMS
 *    hook 看到标记后会把 Bundle 再写死成 FREEFORM=5 + bounds。
 */
public final class FreeformLauncher {
    public static final String EXTRA_FREEFORM =
            "io.github.wumisc.nativewindow.extra.FREEFORM";
    public static final String EXTRA_BOUNDS =
            "io.github.wumisc.nativewindow.extra.BOUNDS";
    public static final String KEY_LAUNCH_WINDOWING_MODE =
            "android.activity.windowingMode";
    public static final String KEY_LAUNCH_BOUNDS = "android:activity.launchBounds";

    private FreeformLauncher() {}

    public static boolean open(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        try {
            PackageManager pm = context.getPackageManager();
            Intent intent = pm.getLaunchIntentForPackage(packageName);
            if (intent == null) return false;
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(EXTRA_FREEFORM, true);
            Rect bounds = defaultBounds(context);
            intent.putExtra(EXTRA_BOUNDS, bounds);

            ActivityOptions options = officialOptions(context, packageName);
            if (options == null) options = ActivityOptions.makeBasic();
            try {
                Method m = ActivityOptions.class.getDeclaredMethod(
                        "setLaunchWindowingMode", int.class);
                m.setAccessible(true);
                m.invoke(options, 5);
            } catch (Throwable ignored) {}
            options.setLaunchBounds(bounds);
            try {
                Object injector = call(options, "getActivityOptionsInjector");
                if (injector != null) call(injector, "setFreeformScale", 0.7f);
            } catch (Throwable ignored) {}
            context.startActivity(intent, options.toBundle());
            return true;
        } catch (Throwable t) {
            android.util.Log.e("NativeWindow/Launch", "open " + packageName + " failed", t);
            return false;
        }
    }

    public static Rect defaultBounds(Context context) {
        int w = context.getResources().getDisplayMetrics().widthPixels;
        int h = context.getResources().getDisplayMetrics().heightPixels;
        int winW = Math.max((int) (w * 0.92f), 320);
        int winH = Math.max((int) (h * 0.68f), 420);
        int left = Math.max(0, (w - winW) / 2);
        int top = Math.max(0, (int) (h * 0.14f));
        return new Rect(left, top, left + winW, Math.min(h, top + winH));
    }

    private static ActivityOptions officialOptions(Context context, String packageName) {
        try {
            Class<?> utils = Class.forName("android.util.MiuiMultiWindowUtils");
            Method m = utils.getDeclaredMethod("getActivityOptions",
                    Context.class, String.class, boolean.class, boolean.class);
            m.setAccessible(true);
            Object o = m.invoke(null, context, packageName, true, false);
            if (o instanceof ActivityOptions) return (ActivityOptions) o;
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object call(Object target, String name, Object... args) throws Exception {
        for (Method m : target.getClass().getMethods()) {
            if (!name.equals(m.getName())) continue;
            if (m.getParameterTypes().length != args.length) continue;
            m.setAccessible(true);
            return m.invoke(target, args);
        }
        return null;
    }
}
