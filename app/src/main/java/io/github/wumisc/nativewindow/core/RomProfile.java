package io.github.wumisc.nativewindow.core;

import android.content.Context;
import android.os.Build;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * ROM 识别与能力画像。
 *
 * 类原生分支之间的 Launcher 包名、私有权限绕过类、非可缩放应用策略并不完全一致。
 * 这里只做保守识别：识别不到就回退 AOSP 通用路径，不在未知 ROM 上强行套用厂商 Hook。
 */
public final class RomProfile {
    public enum Kind {
        PIXELOS,
        LINEAGEOS,
        AOSP,
        UNKNOWN
    }

    public static final String LAUNCHER_LAUNCHER3 = "com.android.launcher3";
    public static final String LAUNCHER_LINEAGE_ALIAS = "org.lineageos.launcher";
    public static final String LAUNCHER_PIXEL = "com.google.android.apps.nexuslauncher";
    public static final String LAUNCHER_QUICKSTEP = "com.android.quickstep";

    private static final List<String> PIXEL_LAUNCHERS = Arrays.asList(
            LAUNCHER_PIXEL, LAUNCHER_QUICKSTEP);
    private static final List<String> LINEAGE_LAUNCHERS = Arrays.asList(
            LAUNCHER_LAUNCHER3, LAUNCHER_LINEAGE_ALIAS, LAUNCHER_QUICKSTEP);

    public final Kind kind;
    public final String displayName;
    public final List<String> launcherPackages;
    public final boolean supportsNonResizableMultiWindow;
    public final boolean pixelPropsBypassAvailable;
    public final float windowWidthRatio;
    public final float windowHeightRatio;
    public final int edgeThresholdDp;

    private RomProfile(Kind kind, String displayName, List<String> launcherPackages,
                       boolean supportsNonResizableMultiWindow,
                       boolean pixelPropsBypassAvailable,
                       float windowWidthRatio, float windowHeightRatio,
                       int edgeThresholdDp) {
        this.kind = kind;
        this.displayName = displayName;
        this.launcherPackages = launcherPackages;
        this.supportsNonResizableMultiWindow = supportsNonResizableMultiWindow;
        this.pixelPropsBypassAvailable = pixelPropsBypassAvailable;
        this.windowWidthRatio = windowWidthRatio;
        this.windowHeightRatio = windowHeightRatio;
        this.edgeThresholdDp = edgeThresholdDp;
    }

    public static RomProfile detect(Context context) {
        String lineageVersion = prop("ro.lineageos.version");
        String pixelOsVersion = prop("ro.pixelos.version");
        String host = lower(Build.HOST);
        String product = lower(Build.PRODUCT);
        String display = lower(Build.DISPLAY);

        boolean lineage = !lineageVersion.isEmpty()
                || host.contains("lineage") || product.contains("lineage")
                || display.contains("lineage");
        boolean pixelOs = !lineage && (!pixelOsVersion.isEmpty()
                || host.contains("pixelos") || product.contains("pixelos")
                || display.contains("pixelos"));

        List<String> launchers = new ArrayList<>();
        Kind kind;
        String name;
        float widthRatio;
        float heightRatio;
        int edgeThreshold;
        if (pixelOs) {
            kind = Kind.PIXELOS;
            name = "PixelOS";
            launchers.addAll(PIXEL_LAUNCHERS);
            widthRatio = 0.82f;
            heightRatio = 0.56f;
            edgeThreshold = 60;
        } else if (lineage) {
            kind = Kind.LINEAGEOS;
            name = "LineageOS";
            launchers.addAll(LINEAGE_LAUNCHERS);
            widthRatio = 0.80f;
            heightRatio = 0.54f;
            edgeThreshold = 56;
        } else {
            kind = Kind.AOSP;
            name = "AOSP";
            launchers.addAll(LINEAGE_LAUNCHERS);
            launchers.addAll(PIXEL_LAUNCHERS);
            widthRatio = 0.82f;
            heightRatio = 0.56f;
            edgeThreshold = 60;
        }
        return new RomProfile(kind, name, distinct(launchers),
                true, kind == Kind.PIXELOS, widthRatio, heightRatio, edgeThreshold);
    }

    public boolean isKnownLauncher(String pkg) {
        if (pkg == null) return false;
        for (String launcher : launcherPackages) {
            if (launcher.equals(pkg)) return true;
        }
        return false;
    }

    public boolean isPixelLauncher(String pkg) {
        return LAUNCHER_PIXEL.equals(pkg) || LAUNCHER_QUICKSTEP.equals(pkg);
    }

    public boolean isLineageLauncher(String pkg) {
        return LAUNCHER_LAUNCHER3.equals(pkg) || LAUNCHER_LINEAGE_ALIAS.equals(pkg);
    }

    public static List<String> knownLauncherPackages() {
        List<String> all = new ArrayList<>();
        all.addAll(PIXEL_LAUNCHERS);
        all.addAll(LINEAGE_LAUNCHERS);
        return distinct(all);
    }

    private static List<String> distinct(List<String> input) {
        List<String> out = new ArrayList<>();
        for (String s : input) {
            if (s != null && !out.contains(s)) out.add(s);
        }
        return out;
    }

    private static String prop(String name) {
        try {
            Class<?> cls = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method m = cls.getMethod("get", String.class);
            return String.valueOf(m.invoke(null, name));
        } catch (Throwable t) {
            return "";
        }
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
