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
            // 探针只保留 NEW_TASK：MULTIPLE_TASK + singleInstance 会让
            // HyperOS 的 freeform 启动判定直接退回全屏。
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Bundle options = buildOptions(context);
            context.startActivity(intent, options);
            return true;
        } catch (Throwable t) {
            ProbeActivity.write(context, false, "launch failed: " + t, null);
            return false;
        }
    }

    private static Bundle buildOptions(Context context) {
        ActivityOptions options = officialOptions(context);
        Rect rect = freeformRect(context);
        StringBuilder steps = new StringBuilder();
        steps.append(options == null ? "official=null" : "official=getActivityOptions");
        if (options == null) options = ActivityOptions.makeBasic();
        try {
            // setLaunchWindowingMode 是 hidden API，只能反射；直接编译会找不到符号。
            Object mode = invoke(options, "setLaunchWindowingMode",
                    new Class[]{int.class}, 5);
            options.setLaunchBounds(rect);
            Object current = invoke(options, "getLaunchWindowingMode", new Class[]{});
            steps.append("mode=").append(current)
                    .append(" bounds=").append(rect.flattenToString());
        } catch (Throwable t) {
            steps.append("windowing api failed=").append(t);
        }
        try {
            Method m = ActivityOptions.class.getDeclaredMethod(
                    "setMiuiConfigFlag", int.class);
            m.setAccessible(true);
            m.invoke(options, 2);
            steps.append(" miuiFlag=2");
        } catch (Throwable t) {
            steps.append(" miuiFlag failed=").append(t);
        }
        try {
            Method m = ActivityOptions.class.getDeclaredMethod(
                    "getActivityOptionsInjector");
            m.setAccessible(true);
            Object injector = m.invoke(options);
            if (injector != null) {
                Method s = injector.getClass().getDeclaredMethod(
                        "setFreeformScale", float.class);
                s.setAccessible(true);
                s.invoke(injector, 0.7f);
                steps.append(" scale=0.7");
            }
        } catch (Throwable t) {
            steps.append(" scale failed=").append(t);
        }
        ProbeActivity.writeBuild(context, steps.toString());
        return options.toBundle();
    }

    /**
     * HyperOS 真正认的是 MiuiMultiWindowUtils.getActivityOptions(context, pkg, true, false)。
     * 手搓 setLaunchWindowingMode + setMiuiConfigFlag 在部分 HyperOS 4 版本会被
     * ActivityStarterInjector 重算回全屏；优先用 MIUI 自己的 options。
     */
    private static ActivityOptions officialOptions(Context context) {
        try {
            Class<?> utils = Class.forName("android.util.MiuiMultiWindowUtils");
            Method m = utils.getDeclaredMethod("getActivityOptions",
                    Context.class, String.class, boolean.class, boolean.class);
            m.setAccessible(true);
            Object o = m.invoke(null, context,
                    context.getPackageName(), true, false);
            if (o instanceof ActivityOptions) return (ActivityOptions) o;
        } catch (Throwable ignored) {}
        return null;
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
