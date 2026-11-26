package io.github.wumisc.nativewindow.hook;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;

import java.lang.reflect.Method;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** 把 NativeCapsuleView 挂到 HyperOS 4 的 MiuiBaseWindowDecoration 顶栏。 */
final class HyperOsCaptionDecorator {
    private static final String TAG = "NativeWindow/HyperOS-Caption";
    private static final String CAPSULE_TAG = "hyperos_native_capsule";

    private HyperOsCaptionDecorator() {}

    static void decorate(Object decoration, View top) {
        if (decoration == null || top == null) return;
        if (top.findViewWithTag(CAPSULE_TAG) != null) return;
        int taskId = taskId(decoration);
        if (taskId <= 0) {
            XposedBridge.log(TAG + ": no taskId, skip capsule");
            return;
        }
        if (!(top instanceof ViewGroup)) {
            XposedBridge.log(TAG + ": top caption is not a ViewGroup, skip capsule");
            return;
        }
        Context context = top.getContext();
        String label = label(decoration);
        NativeCapsuleView capsule = new NativeCapsuleView(context,
                new NativeCapsuleView.Listener() {
                    @Override public void onMini() { HyperOsWindowActions.mini(context, taskId); }
                    @Override public void onFullscreen() {
                        HyperOsWindowActions.fullscreen(context, taskId);
                    }
                    @Override public void onClose() { SystemUiHook.closeTask(taskId); }
                });
        capsule.setTag(CAPSULE_TAG);
        capsule.bind(label, "");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 36));
        lp.leftMargin = dp(context, 6);
        lp.rightMargin = dp(context, 6);
        lp.topMargin = dp(context, 2);
        lp.bottomMargin = dp(context, 2);
        try {
            ((ViewGroup) top).addView(capsule, lp);
            XposedBridge.log(TAG + ": capsule attached to HyperOS task=" + taskId);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": add capsule failed " + t);
        }
    }

    private static int taskId(Object decoration) {
        for (String name : new String[]{"mTaskInfo", "mRunningTaskInfo", "mTask"}) {
            Object o = field(decoration, name);
            if (o == null) continue;
            int id = intField(o, "taskId", -1);
            if (id > 0) return id;
            id = intField(o, "mTaskId", -1);
            if (id > 0) return id;
            try {
                Object r = XposedHelpers.callMethod(o, "getTaskId");
                if (r instanceof Integer && (Integer) r > 0) return (Integer) r;
            } catch (Throwable ignored) {}
        }
        return intField(decoration, "mTaskId", -1);
    }

    private static String label(Object decoration) {
        for (String name : new String[]{"mTaskInfo", "mRunningTaskInfo", "mTask"}) {
            Object o = field(decoration, name);
            if (o == null) continue;
            try {
                Object desc = XposedHelpers.getObjectField(o, "taskDescription");
                Object l = XposedHelpers.callMethod(desc, "getLabel");
                if (l != null) return String.valueOf(l);
            } catch (Throwable ignored) {}
        }
        return "";
    }

    private static Object field(Object target, String name) {
        try { return XposedHelpers.getObjectField(target, name); }
        catch (Throwable t) { return null; }
    }

    private static int intField(Object target, String name, int def) {
        try { return XposedHelpers.getIntField(target, name); }
        catch (Throwable t) { return def; }
    }

    private static int dp(Context context, int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }
}

/** HyperOS 4 的胶囊按钮动作；失败只打日志，不崩 SystemUI。 */
final class HyperOsWindowActions {
    private HyperOsWindowActions() {}

    static void mini(Context context, int taskId) {
        try {
            Rect max = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE))
                    .getMaximumWindowMetrics().getBounds();
            int w = Math.max(dp(context, 220), (int) (max.width() * 0.32f));
            int h = Math.max(dp(context, 150), (int) (max.height() * 0.18f));
            int left = dp(context, 12);
            int top = dp(context, 96);
            Rect mini = new Rect(left, top, left + w, top + h);
            resizeTask(taskId, mini);
        } catch (Throwable t) {
            XposedBridge.log("NativeWindow/HyperOS: mini failed " + t);
        }
    }

    static void fullscreen(Context context, int taskId) {
        try {
            Intent i = new Intent("miui.intent.action_launch_fullscreen_from_freeform");
            i.putExtra("taskId", taskId);
            i.putExtra("task_id", taskId);
            context.sendBroadcast(i);
        } catch (Throwable t) {
            XposedBridge.log("NativeWindow/HyperOS: fullscreen failed " + t);
        }
    }

    private static void resizeTask(int taskId, Rect bounds) {
        try {
            Class<?> atm = Class.forName("android.app.ActivityTaskManager");
            Object service = atm.getMethod("getService").invoke(null);
            Method m = service.getClass().getMethod("resizeTask",
                    int.class, Rect.class, int.class);
            m.invoke(service, taskId, bounds, 0);
            XposedBridge.log("NativeWindow/HyperOS: resize task=" + taskId
                    + " -> " + bounds.flattenToString());
        } catch (Throwable t) {
            XposedBridge.log("NativeWindow/HyperOS: resize failed " + t);
        }
    }

    private static int dp(Context context, int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }
}
