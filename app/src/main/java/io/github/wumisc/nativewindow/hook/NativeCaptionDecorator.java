package io.github.wumisc.nativewindow.hook;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 把 HyperOS 胶囊塞进 AOSP 原生 CaptionWindowDecoration。
 *
 * 为什么这样做：0.3.0 的胶囊是应用悬浮窗，和 freeform Task 是两个 Surface；
 * 拖动时一定是“胶囊先动、任务后动”，圆角/阴影也会分家。原生 caption 是 Task 的
 * WindowDecorViewHost，天然随任务移动/缩放/裁剪，才可能做到国产 UI 的一体感。
 *
 * 这里只改原生 caption 的 View 树：
 * - 原生 back/min/max/close 按钮隐藏，但保留，避免 CaptionWindowDecorViewModel 的 findViewById 空指针；
 * - 根背景透明，去掉 AOSP 标题栏底色；
 * - 加一条 NativeCapsuleView；按钮直接调 SystemUiHook 的 WCT。
 * 拖动继续由 CaptionWindowDecoration 原生 OnTouchListener 处理。
 */
final class NativeCaptionDecorator {
    private static final String TAG = "NativeWindow/Caption";
    private static final String CAPSULE_TAG = "native_window_capsule";

    private NativeCaptionDecorator() {}

    static void decorate(Object decoration, View root) {
        if (decoration == null || root == null) return;
        if (root.findViewWithTag(CAPSULE_TAG) != null) return;

        Object taskInfo;
        try {
            taskInfo = XposedHelpers.getObjectField(decoration, "mTaskInfo");
        } catch (Throwable t) {
            return;
        }
        if (taskInfo == null) return;
        int taskId = intField(taskInfo, "taskId", -1);
        if (taskId <= 0) return;

        Context context = root.getContext();
        // 不能把背景置 null：CaptionWindowDecoration#setupCaptionColor 会强转 GradientDrawable。
        // 保留 drawable，只把颜色刷透明；后面还会 Hook setupCaptionColor/setCaptionColor 再刷一次。
        clearCaptionBackground(decoration, root);
        root.setPadding(0, 0, 0, 0);

        hide(root, context, "back_button");
        hide(root, context, "minimize_window");
        hide(root, context, "maximize_window");
        hide(root, context, "close_window");

        String label = "";
        String pkg = "";
        try {
            Object desc = XposedHelpers.getObjectField(taskInfo, "taskDescription");
            Object l = XposedHelpers.callMethod(desc, "getLabel");
            if (l != null) label = String.valueOf(l);
        } catch (Throwable ignored) {}
        try {
            Object base = XposedHelpers.getObjectField(taskInfo, "baseActivity");
            Object p = XposedHelpers.callMethod(base, "getPackageName");
            if (p != null) pkg = String.valueOf(p);
        } catch (Throwable ignored) {}

        NativeCapsuleView capsule = new NativeCapsuleView(context,
                new NativeCapsuleView.Listener() {
                    @Override public void onMini() { SystemUiHook.toggleMini(taskId); }
                    @Override public void onFullscreen() { SystemUiHook.fullscreenTask(taskId); }
                    @Override public void onClose() { SystemUiHook.closeTask(taskId); }
                });
        capsule.setTag(CAPSULE_TAG);
        capsule.bind(label, pkg);

        int h = dp(context, 38);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h);
        lp.leftMargin = dp(context, 6);
        lp.rightMargin = dp(context, 6);
        lp.topMargin = dp(context, 4);
        lp.bottomMargin = dp(context, 4);
        try {
            ((LinearLayout) root).addView(capsule, lp);
            XposedBridge.log(TAG + ": capsule attached to task=" + taskId);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": add capsule failed " + t);
        }
    }

    static void clearCaptionBackground(Object decoration, View root) {
        try {
            if (root == null) {
                Object result = XposedHelpers.getObjectField(decoration, "mResult");
                Object rv = XposedHelpers.getObjectField(result, "mRootView");
                if (rv instanceof View) root = (View) rv;
            }
            if (root == null) return;
            Context context = root.getContext();
            int id = context.getResources().getIdentifier("caption", "id", context.getPackageName());
            View caption = id != 0 ? root.findViewById(id) : root;
            if (caption == null) return;
            Drawable bg = caption.getBackground();
            // 只改 GradientDrawable 的颜色，绝不替换成 ColorDrawable：
            // CaptionWindowDecoration#setupCaptionColor 后面还会强转 GradientDrawable。
            if (bg instanceof GradientDrawable) {
                ((GradientDrawable) bg).setColor(Color.TRANSPARENT);
            }
        } catch (Throwable ignored) {}
    }

    private static void hide(View root, Context context, String idName) {
        try {
            int id = context.getResources().getIdentifier(idName, "id", context.getPackageName());
            if (id == 0) return;
            View v = root.findViewById(id);
            if (v != null) {
                v.setVisibility(View.GONE);
            }
        } catch (Throwable ignored) {}
    }

    private static int intField(Object target, String field, int def) {
        try {
            return XposedHelpers.getIntField(target, field);
        } catch (Throwable t) {
            return def;
        }
    }

    private static int dp(Context context, int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }
}
