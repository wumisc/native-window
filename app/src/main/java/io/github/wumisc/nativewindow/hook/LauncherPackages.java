package io.github.wumisc.nativewindow.hook;

import io.github.wumisc.nativewindow.core.RomProfile;

/** PixelOS / LineageOS 两套桌面的包名入口。 */
public final class LauncherPackages {
    public static final String LOS = RomProfile.LAUNCHER_LAUNCHER3;
    public static final String PIXEL = RomProfile.LAUNCHER_PIXEL;

    private LauncherPackages() {}

    public static boolean isLauncher(String pkg) {
        return RomProfile.knownLauncherPackages().contains(pkg);
    }

    public static boolean isPixelLauncher(String pkg) {
        return RomProfile.knownLauncherPackages().contains(pkg)
                && (RomProfile.LAUNCHER_PIXEL.equals(pkg)
                || RomProfile.LAUNCHER_QUICKSTEP.equals(pkg));
    }

    public static String displayName(String pkg) {
        if (LOS.equals(pkg) || RomProfile.LAUNCHER_LINEAGE_ALIAS.equals(pkg)) {
            return "Trebuchet / Launcher3";
        }
        if (PIXEL.equals(pkg) || RomProfile.LAUNCHER_QUICKSTEP.equals(pkg)) {
            return "Pixel Launcher";
        }
        return pkg;
    }
}
