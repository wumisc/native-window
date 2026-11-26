package io.github.wumisc.nativewindow.hook;

/** PixelOS / LineageOS 两套桌面的包名入口。 */
public final class LauncherPackages {
    public static final String LOS = "com.android.launcher3";
    public static final String PIXEL = "com.google.android.apps.nexuslauncher";

    private LauncherPackages() {}

    public static boolean isLauncher(String pkg) {
        return LOS.equals(pkg) || PIXEL.equals(pkg);
    }

    public static String displayName(String pkg) {
        if (LOS.equals(pkg)) return "Trebuchet / Launcher3";
        if (PIXEL.equals(pkg)) return "Pixel Launcher";
        return pkg;
    }
}
