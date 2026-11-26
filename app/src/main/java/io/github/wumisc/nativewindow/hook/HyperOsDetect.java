package io.github.wumisc.nativewindow.hook;

import android.os.Build;

import java.lang.reflect.Method;

import de.robv.android.xposed.XposedHelpers;

/** Hook 进程里的 HyperOS 判断；不依赖 miui.os.Build 的编译期符号。 */
final class HyperOsDetect {
    private HyperOsDetect() {}

    static boolean isHyperOs(ClassLoader cl) {
        try {
            if (XposedHelpers.findClassIfExists("miui.os.Build", cl) != null) return true;
        } catch (Throwable ignored) {}
        try {
            Class<?> sp = XposedHelpers.findClass("android.os.SystemProperties", cl);
            Method get = sp.getMethod("get", String.class, String.class);
            Object v = get.invoke(null, "ro.mi.os.version.code", "");
            if (v instanceof String && !((String) v).isEmpty()) return true;
        } catch (Throwable ignored) {}
        return Build.MANUFACTURER.equalsIgnoreCase("Xiaomi")
                && Build.DISPLAY != null && Build.DISPLAY.startsWith("CP2");
    }
}
