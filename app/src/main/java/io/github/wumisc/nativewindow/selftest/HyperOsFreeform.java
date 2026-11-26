package io.github.wumisc.nativewindow.selftest;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;

import java.lang.reflect.Method;

/**
 * HyperOS 4 的 freeform 启动器。
 *
 * 依据 HyperOS 4 的公开可见行为（MaxFreeForm / HyperCeiler 的适配记录）：
 * - ActivityOptions 需要 setLaunchWindowingMode(5)
 * - setMiuiConfigFlag(2)
 * - setLaunchBounds(MiuiMultiWindowUtils.getFreeformRect(context))
 * - getActivityOptionsInjector().setFreeformScale(0.7f)
 *
 * 这里全部用反射，避免把 MIUI 私有 API 编进 DEX；任何一步失败都回退到普通
 * ActivityOptions，让 ProbeActivity 至少能起来并报告“全屏而不是小窗”。
 */
public final class HyperOsFreeform {
    private HyperOsFreeform() {}

    public static boolean launchProbe(Context context) {
        try {
            Intent intent = new Intent(context, ProbeActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            Bundle options = buildOptions(context);
            context.startActivity(intent, options);
            return true;
        } catch (Throwable t) {
            ProbeActivity.write(context, false, "launch failed: " + t, null);
            return false;
        }
    }

    private static Bundle buildOptions(Context context) {
        ActivityOptions options = ActivityOptions.makeBasic();
        Rect rect = freeformRect(context);
        invoke(options, "setLaunchWindowingMode", new Class[]{int.class}, 5);
        invoke(options, "setMiuiConfigFlag", new Class[]{int.class}, 2);
        invoke(options, "setLaunchBounds", new Class[]{Rect.class}, rect);
        try {
            Object injector = invoke(options, "getActivityOptionsInjector", new Class[]{});
            if (injector != null) {
                invoke(injector, "setFreeformScale", new Class[]{float.class}, 0.7f);
            }
        } catch (Throwable ignored) {}
        return options.toBundle();
    }

    private static Rect freeformRect(Context context) {
        try {
            Class<?> utils = Class.forName("android.util.MiuiMultiWindowUtils");
            for (Method m : utils.getDeclaredMethods()) {
                if (!"getFreeformRect".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0] == Context.class) {
                    m.setAccessible(true);
                    Object r = m.invoke(null, context);
                    if (r instanceof Rect) return new Rect((Rect) r);
                }
            }
        } catch (Throwable ignored) {}
        Rect max = new Rect(0, 0, 1080, 2400);
        try {
            android.view.WindowManager wm =
                    (android.view.WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            max = new Rect(wm.getMaximumWindowMetrics().getBounds());
        } catch (Throwable ignored) {}
        int w = Math.max(320, (int) (max.width() * 0.78f));
        int h = Math.max(420, (int) (max.height() * 0.62f));
        int left = Math.max(0, (max.width() - w) / 2);
        int top = Math.max(0, (max.height() - h) / 2);
        return new Rect(left, top, left + w, top + h);
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) {
        try {
            Method m = target.getClass().getMethod(name, types);
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
