package io.github.wumisc.nativewindow.hook;

import android.app.ActivityOptions;
import android.content.Context;
import android.graphics.Rect;
import android.util.Log;

import java.lang.reflect.Method;

/** AOSP 隐藏 API 的薄封装；真机上失败时只回退，不抛给 Launcher。 */
public final class SmallWindowBridge {
    private static final String TAG = "NativeWindow/Bridge";
    private static final int WINDOWING_MODE_FREEFORM = 1;

    private SmallWindowBridge() {}

    public static boolean openFromTask(Context context, int taskId) {
        try {
            ActivityOptions options = ActivityOptions.makeBasic();
            try {
                Method m = ActivityOptions.class.getDeclaredMethod(
                        "setLaunchWindowingMode", int.class);
                m.setAccessible(true);
                m.invoke(options, WINDOWING_MODE_FREEFORM);
            } catch (Throwable ignored) {
                // 没有该隐藏方法时，至少给一个 freeform bounds
            }
            int w = context.getResources().getDisplayMetrics().widthPixels;
            int h = context.getResources().getDisplayMetrics().heightPixels;
            options.setLaunchBounds(new Rect(
                    (int) (w * 0.12f), (int) (h * 0.16f),
                    (int) (w * 0.88f), (int) (h * 0.78f)));

            Class<?> atmClass = Class.forName("android.app.ActivityTaskManager");
            Object atm = atmClass.getMethod("getService").invoke(null);
            Method start = atm.getClass().getMethod(
                    "startActivityFromRecents", int.class, ActivityOptions.class);
            start.invoke(atm, taskId, options);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "openFromTask failed", t);
            return false;
        }
    }
}
