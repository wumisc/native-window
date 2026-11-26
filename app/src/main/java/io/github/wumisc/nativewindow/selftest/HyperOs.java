package io.github.wumisc.nativewindow.selftest;

import android.os.Build;

import java.lang.reflect.Method;

/** 轻量 HyperOS 判断：不依赖 miui.os.Build 的编译期符号。 */
public final class HyperOs {
    private HyperOs() {}

    public static boolean isHyperOs() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            Object v = get.invoke(null, "ro.mi.os.version.code", "");
            if (v instanceof String && !((String) v).isEmpty()) return true;
        } catch (Throwable ignored) {}
        // 兜底：HyperOS 4 的 CP2A/CP2B build 与 Xiaomi 品牌。
        return Build.MANUFACTURER.equalsIgnoreCase("Xiaomi")
                && Build.DISPLAY != null
                && (Build.DISPLAY.startsWith("CP2") || Build.DISPLAY.startsWith("OS"));
    }

    public static String describe() {
        String version = "";
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            Object v = get.invoke(null, "ro.mi.os.version.code", "");
            if (v instanceof String) version = (String) v;
        } catch (Throwable ignored) {}
        return "HyperOS" + (version.isEmpty() ? "" : " " + version)
                + " · " + Build.DISPLAY;
    }
}
