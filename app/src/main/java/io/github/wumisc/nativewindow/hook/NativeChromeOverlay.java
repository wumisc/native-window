package io.github.wumisc.nativewindow.hook;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Android 15/16 没有 freeform 原生 caption（WindowDecoration 只在桌面模式创建），
 * 手机上用 Task 打开自由窗口后只有一块裸内容，不能拖、不能缩放、不能关。
 *
 * 这里补一层“窗口外壳”：
 * - 顶部胶囊（原生 NativeCapsuleView）负责拖动、双击换尺寸、最小化/全屏/关闭；
 * - 右下角抓手负责缩放；
 * - 所有几何变化仍然走 ShellTaskOrganizer + WCT，改的是真实 Task bounds，不是假窗。
 *
 * 这是一层独立的 TYPE_APPLICATION_OVERLAY，只在 API < 36 且原生 caption 不会出现时启用。
 * AOSP 17 / PixelOS 17 上继续用 NativeCaptionDecorator 嵌进原生 caption，不叠加这套。
 */
final class NativeChromeOverlay {
    private static final String TAG = "NativeWindow/Chrome";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static WindowManager sWm;
    private static NativeCapsuleView sCapsule;
    private static TextView sHandle;
    private static WindowManager.LayoutParams sCapsuleLp;
    private static WindowManager.LayoutParams sHandleLp;

    private static int sTaskId = -1;
    private static Rect sBounds;
    private static volatile boolean sAliveCheckPosted;

    private static Rect sStartBounds;
    private static float sDownRawX;
    private static float sDownRawY;
    private static long sDownTime;
    private static long sLastTapTime;
    private static boolean sDragging;
    private static long sLastApply;

    private NativeChromeOverlay() {}

    static void showForTask(int taskId, Rect bounds) {
        if (Build.VERSION.SDK_INT >= 36) return; // API 36+ 用原生 caption，避免两层皮
        Context ctx = SystemUiHook.context();
        Object info = SystemUiHook.info(taskId);
        if (ctx == null || info == null) return;
        Rect b = bounds != null ? new Rect(bounds) : SystemUiHook.taskBounds(taskId);
        if (b == null || b.isEmpty()) return;
        String label = "";
        String pkg = "";
        try {
            Object desc = XposedHelpers.getObjectField(info, "taskDescription");
            Object l = XposedHelpers.callMethod(desc, "getLabel");
            if (l != null) label = String.valueOf(l);
        } catch (Throwable ignored) {}
        try {
            Object top = XposedHelpers.getObjectField(info, "topActivity");
            if (top instanceof android.content.ComponentName) {
                pkg = ((android.content.ComponentName) top).getPackageName();
            }
        } catch (Throwable ignored) {}
        show(ctx, taskId, b, label, pkg);
        XposedBridge.log(TAG + ": overlay shown task=" + taskId + " bounds=" + b);
    }

    private static void show(Context ctx, int taskId, Rect bounds, String label, String pkg) {
        if (sTaskId == taskId && sCapsule != null && sBounds != null) {
            sync(taskId, bounds);
            return;
        }
        hide();
        try {
            sWm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            sTaskId = taskId;
            sBounds = new Rect(bounds);

            sCapsule = new NativeCapsuleView(ctx, new NativeCapsuleView.Listener() {
                @Override public void onMini() {
                    SystemUiHook.toggleMini(taskId);
                    postSync(taskId);
                }
                @Override public void onFullscreen() {
                    sTaskId = -1;
                    hide();
                    SystemUiHook.fullscreenTask(taskId);
                }
                @Override public void onClose() {
                    sTaskId = -1;
                    hide();
                    SystemUiHook.closeTask(taskId);
                }
            });
            sCapsule.bind(label, pkg);
            sCapsule.setOnTouchListener(new DragTouch());

            sCapsuleLp = baseParams(dp(ctx, 236), dp(ctx, 44));
            sCapsuleLp.setTitle("NativeWindow capsule");
            sWm.addView(sCapsule, sCapsuleLp);

            sHandle = new TextView(ctx);
            sHandle.setText("⌟");
            sHandle.setTextSize(16);
            sHandle.setTextColor(0xCC1A1A1A);
            GradientDrawable hb = new GradientDrawable();
            hb.setColor(0xD9FFFFFF);
            hb.setCornerRadius(dp(ctx, 8));
            hb.setStroke(dp(ctx, 1), 0x33000000);
            sHandle.setBackground(hb);
            sHandle.setGravity(Gravity.CENTER);
            sHandle.setOnTouchListener(new ResizeTouch());

            sHandleLp = baseParams(dp(ctx, 28), dp(ctx, 28));
            sHandleLp.setTitle("NativeWindow resize");
            sWm.addView(sHandle, sHandleLp);

            layout();
            startAliveCheck();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": overlay add failed " + t);
            hide();
        }
    }

    static void sync(int taskId, Rect bounds) {
        if (taskId != sTaskId || bounds == null) return;
        sBounds = new Rect(bounds);
        layout();
    }

    private static void postSync(int taskId) {
        MAIN.postDelayed(() -> {
            Rect b = SystemUiHook.taskBounds(taskId);
            if (b != null) sync(taskId, b);
        }, 180);
    }

    private static void layout() {
        if (sWm == null || sCapsule == null || sBounds == null) return;
        int w = Math.min(sCapsuleLp.width, Math.max(dp(sCapsule.getContext(), 180),
                sBounds.width() - dp(sCapsule.getContext(), 12)));
        sCapsuleLp.width = w;
        sCapsuleLp.x = Math.max(0, sBounds.centerX() - w / 2);
        sCapsuleLp.y = Math.max(0, sBounds.top + dp(sCapsule.getContext(), 4));
        try { sWm.updateViewLayout(sCapsule, sCapsuleLp); } catch (Throwable ignored) {}

        if (sHandle != null && sHandleLp != null) {
            sHandleLp.x = Math.max(0, sBounds.right - sHandleLp.width - dp(sHandle.getContext(), 4));
            sHandleLp.y = Math.max(0, sBounds.bottom - sHandleLp.height - dp(sHandle.getContext(), 4));
            try { sWm.updateViewLayout(sHandle, sHandleLp); } catch (Throwable ignored) {}
        }
    }

    static void hide() {
        WindowManager wm = sWm;
        if (wm != null) {
            try { if (sCapsule != null) wm.removeViewImmediate(sCapsule); } catch (Throwable ignored) {}
            try { if (sHandle != null) wm.removeViewImmediate(sHandle); } catch (Throwable ignored) {}
        }
        sCapsule = null;
        sHandle = null;
        sCapsuleLp = null;
        sHandleLp = null;
        sBounds = null;
        sTaskId = -1;
    }

    static void hideTask(int taskId) {
        if (taskId == sTaskId) hide();
    }

    private static WindowManager.LayoutParams baseParams(int w, int h) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        return lp;
    }

    private static void startAliveCheck() {
        if (sAliveCheckPosted) return;
        sAliveCheckPosted = true;
        MAIN.postDelayed(new Runnable() {
            @Override public void run() {
                sAliveCheckPosted = false;
                if (sTaskId <= 0) return;
                Object info = SystemUiHook.info(sTaskId);
                if (info == null) {
                    hide();
                    return;
                }
                Rect b = SystemUiHook.taskBounds(sTaskId);
                if (b != null) sync(sTaskId, b);
                startAliveCheck();
            }
        }, 1000);
    }

    private static void apply(Rect b, boolean finalApply) {
        if (sTaskId <= 0 || b == null) return;
        sBounds = new Rect(b);
        layout();
        long now = android.os.SystemClock.uptimeMillis();
        if (!finalApply && now - sLastApply < 28) return;
        sLastApply = now;
        SystemUiHook.applyBounds(sTaskId, b);
    }

    private static Rect clamp(Rect r, Context ctx) {
        Rect screen = SystemUiHook.screen();
        if (screen == null) return r;
        int w = r.width();
        int h = r.height();
        if (r.left < 0) r.offset(-r.left, 0);
        if (r.top < 0) r.offset(0, -r.top);
        if (r.right > screen.right) r.offset(screen.right - r.right, 0);
        if (r.bottom > screen.bottom) r.offset(0, screen.bottom - r.bottom);
        if (r.left < 0) r.left = 0;
        if (r.top < 0) r.top = 0;
        if (r.right > screen.right) r.right = screen.right;
        if (r.bottom > screen.bottom) r.bottom = screen.bottom;
        return r;
    }

    /** 胶囊左侧是拖动区，右侧约 112dp 留给三个按钮。 */
    private static final class DragTouch implements View.OnTouchListener {
        @Override public boolean onTouch(View v, MotionEvent e) {
            if (sBounds == null) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (e.getX() > v.getWidth() - dp(v.getContext(), 112)) return false;
                    sStartBounds = new Rect(sBounds);
                    sDownRawX = e.getRawX();
                    sDownRawY = e.getRawY();
                    sDownTime = android.os.SystemClock.uptimeMillis();
                    sDragging = true;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!sDragging || sStartBounds == null) return false;
                    Rect nb = new Rect(sStartBounds);
                    nb.offset((int) (e.getRawX() - sDownRawX), (int) (e.getRawY() - sDownRawY));
                    apply(clamp(nb, v.getContext()), false);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!sDragging || sStartBounds == null) return false;
                    sDragging = false;
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - sDownTime < 260) {
                        if (now - sLastTapTime < 320) {
                            sLastTapTime = 0;
                            cycleSize();
                        } else {
                            sLastTapTime = now;
                        }
                    } else {
                        snapToEdge(v.getContext());
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    sDragging = false;
                    return true;
            }
            return false;
        }
    }

    private static void cycleSize() {
        if (sBounds == null) return;
        int screenW = SystemUiHook.screen() != null ? SystemUiHook.screen().width() : 1080;
        int[] fractions = {60, 82, 94};
        Rect b = new Rect(sBounds);
        int current = Math.round(b.width() * 100f / screenW);
        int next = fractions[0];
        for (int i = 0; i < fractions.length; i++) {
            if (current < fractions[i]) { next = fractions[i]; break; }
        }
        if (current >= fractions[fractions.length - 1]) next = fractions[0];
        int newW = screenW * next / 100;
        int cx = b.centerX();
        b.left = cx - newW / 2;
        b.right = b.left + newW;
        apply(clamp(b, sCapsule != null ? sCapsule.getContext() : null), true);
    }

    private static void snapToEdge(Context ctx) {
        if (sBounds == null || SystemUiHook.screen() == null) return;
        Rect screen = SystemUiHook.screen();
        Rect b = new Rect(sBounds);
        int threshold = dp(ctx, 18);
        if (b.left < threshold) {
            b.offset(-b.left, 0);
        } else if (screen.right - b.right < threshold) {
            b.offset(screen.right - b.right, 0);
        }
        apply(b, true);
    }

    private static final class ResizeTouch implements View.OnTouchListener {
        @Override public boolean onTouch(View v, MotionEvent e) {
            if (sBounds == null) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    sStartBounds = new Rect(sBounds);
                    sDownRawX = e.getRawX();
                    sDownRawY = e.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (sStartBounds == null) return false;
                    Rect nb = new Rect(sStartBounds);
                    int minW = dp(v.getContext(), 300);
                    int minH = dp(v.getContext(), 240);
                    nb.right = Math.max(nb.left + minW, sStartBounds.right + (int) (e.getRawX() - sDownRawX));
                    nb.bottom = Math.max(nb.top + minH, sStartBounds.bottom + (int) (e.getRawY() - sDownRawY));
                    apply(clamp(nb, v.getContext()), false);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (sBounds != null) apply(sBounds, true);
                    sStartBounds = null;
                    return true;
            }
            return false;
        }
    }

    private static int dp(Context ctx, int v) {
        if (ctx == null) return v;
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
