package io.github.wumisc.nativewindow.hook;

import android.app.ActivityOptions;
import android.content.Context;
import android.graphics.Rect;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * AOSP 隐藏 API 的薄封装。
 *
 * 关键结论（Android 17 / API 37 源码核对）：
 * - {@code WindowConfiguration.WINDOWING_MODE_FREEFORM == 5}，不是 1；1 是 FULLSCREEN。
 * - {@code IActivityTaskManager.startActivityFromRecents(int, Bundle)} 的第二个参数是 Bundle，
 *   不是 ActivityOptions。直接传 ActivityOptions 在真机会 MethodNotFound。
 * - Bundle 里放 {@code android.activity.windowingMode=5} 和
 *   {@code android:activity.launchBounds=Rect}，ActivityStarter 会按 freeform 解析。
 *
 * 本类只在 Launcher 进程调用。Launcher（Launcher3 / Pixel Launcher）持有
 * START_TASKS_FROM_RECENTS 与 MANAGE_ACTIVITY_TASKS，所以不需要模块 App 去申请签名权限。
 */
public final class SmallWindowBridge {
    private static final String TAG = "NativeWindow/Bridge";

    /** WindowConfiguration.WINDOWING_MODE_FREEFORM */
    public static final int WINDOWING_MODE_FREEFORM = 5;
    /** WindowConfiguration.WINDOWING_MODE_FULLSCREEN */
    public static final int WINDOWING_MODE_FULLSCREEN = 1;

    /** ActivityOptions.KEY_LAUNCH_WINDOWING_MODE（@hide，但字符串稳定） */
    public static final String KEY_LAUNCH_WINDOWING_MODE = "android.activity.windowingMode";
    /** ActivityOptions.KEY_LAUNCH_BOUNDS（public） */
    public static final String KEY_LAUNCH_BOUNDS = "android:activity.launchBounds";

    private SmallWindowBridge() {}

    /** 用屏幕比例给一个默认小窗 bounds，避免不同 DPI 下写死像素。 */
    public static Rect defaultBounds(Context context) {
        int w = context.getResources().getDisplayMetrics().widthPixels;
        int h = context.getResources().getDisplayMetrics().heightPixels;
        return new Rect(
                (int) (w * 0.10f), (int) (h * 0.16f),
                (int) (w * 0.90f), (int) (h * 0.74f));
    }

    public static boolean openFromTask(Context context, int taskId) {
        return openFromTask(context, taskId, defaultBounds(context));
    }

    /**
     * 从最近任务以 freeform 打开。
     *
     * 注意：这里故意用 Bundle 而不是 ActivityOptions。Android 17 的 AIDL 签名就是 Bundle。
     */
    public static boolean openFromTask(Context context, int taskId, Rect bounds) {
        if (taskId <= 0) return false;
        try {
            ActivityOptions options = ActivityOptions.makeBasic();
            if (bounds != null) options.setLaunchBounds(bounds);

            android.os.Bundle bundle = options.toBundle();
            bundle.putInt(KEY_LAUNCH_WINDOWING_MODE, WINDOWING_MODE_FREEFORM);
            if (bounds != null) bundle.putParcelable(KEY_LAUNCH_BOUNDS, bounds);

            Object atm = activityTaskManager();
            Method start = atm.getClass().getMethod(
                    "startActivityFromRecents", int.class, android.os.Bundle.class);
            start.invoke(atm, taskId, bundle);
            Log.i(TAG, "freeform open task=" + taskId + " bounds=" + bounds);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "openFromTask failed task=" + taskId, t);
            return false;
        }
    }

    /**
     * 把已有任务改成 freeform。用于“最近任务菜单进入”之后，系统仍按 fullscreen 启动的回退。
     * 需要 Launcher 的 MANAGE_ACTIVITY_TASKS。
     */
    public static boolean resizeTask(Context context, int taskId, Rect bounds) {
        if (taskId <= 0 || bounds == null) return false;
        try {
            Object atm = activityTaskManager();
            Method resize = atm.getClass().getMethod(
                    "resizeTask", int.class, Rect.class, int.class);
            resize.invoke(atm, taskId, bounds, 0 /* userId */);
            Log.i(TAG, "resize task=" + taskId + " bounds=" + bounds);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "resizeTask failed task=" + taskId, t);
            return false;
        }
    }

    private static Object activityTaskManager() throws Exception {
        Class<?> cls = Class.forName("android.app.ActivityTaskManager");
        return cls.getMethod("getService").invoke(null);
    }
}
