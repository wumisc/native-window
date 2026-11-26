package io.github.wumisc.nativewindow;

import android.content.Context;
import android.content.res.Configuration;

/** 模块 UI 的日间/夜间配色；跟系统 uiMode 走，不跟皮肤走。 */
public final class Palette {
    private Palette() {}

    public static boolean isNight(Context context) {
        int mode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    public static int bg(Context c) { return isNight(c) ? 0xFF08090C : 0xFFF4F4F7; }
    public static int fg(Context c) { return isNight(c) ? 0xFFF2F2F7 : 0xFF17171A; }
    public static int secondary(Context c) { return isNight(c) ? 0xFFB8B8BE : 0xFF5F5F66; }
    public static int tertiary(Context c) { return isNight(c) ? 0xFF7C7C82 : 0xFF8A8A90; }
    public static int button(Context c) { return isNight(c) ? 0xFF1C1C1E : 0xFFFFFFFF; }
    public static int buttonText(Context c) { return isNight(c) ? 0xFFF2F2F7 : 0xFF17171A; }
    public static int buttonStroke(Context c) { return isNight(c) ? 0x33FFFFFF : 0x1A000000; }
    public static int danger(Context c) { return isNight(c) ? 0xFFFF6B6B : 0xFFD70015; }
    public static int dim(Context c) { return isNight(c) ? 0x99000000 : 0x55000000; }

    public static int card(Context c) { return isNight(c) ? 0xF21C1C1E : 0xF7FFFFFF; }
    public static int capsule(Context c) { return isNight(c) ? 0xCC2C2C2E : 0xE6FFFFFF; }
    public static int cardStroke(Context c) { return isNight(c) ? 0x22FFFFFF : 0x14000000; }
    public static int iconPlate(Context c) { return isNight(c) ? 0x22FFFFFF : 0x11000000; }
    public static int handle(Context c) { return isNight(c) ? 0x66FFFFFF : 0x55000000; }
    public static int sidebar(Context c) { return isNight(c) ? 0xEE141416 : 0xEEFFFFFF; }
    public static int sidebarStroke(Context c) { return isNight(c) ? 0x22FFFFFF : 0x14000000; }
    public static int sidebarIcon(Context c) { return isNight(c) ? 0x1FFFFFFF : 0x0D000000; }
    public static int sidebarHandle(Context c) { return isNight(c) ? 0xB3FFFFFF : 0x99000000; }
}
