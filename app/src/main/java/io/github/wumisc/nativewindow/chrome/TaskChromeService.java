package io.github.wumisc.nativewindow.chrome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import io.github.wumisc.nativewindow.hook.SystemUiHook;

/**
 * 真实 freeform 任务的系统 chrome。
 *
 * 和 0.2.0 的 DemoOverlay 不同：这里不再画一个假卡片，而是把胶囊/底条贴到
 * SystemUI 上报的真实 Task bounds 上；拖动/缩放通过 SystemUiHook 的 WCT 改任务边界。
 */
public final class TaskChromeService extends Service {
    public static final String ACTION_START = "io.github.wumisc.nativewindow.action.START_CHROME";
    public static final String ACTION_STOP = "io.github.wumisc.nativewindow.action.STOP_CHROME";
    public static final String EXTRA_TASK_ID = "task_id";

    private static final int NOTIFICATION_ID = 42;
    private static final String CHANNEL = "native_window_chrome";
    private static final String SUI_PKG = "com.android.systemui";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private TaskCapsuleView capsule;
    private View bottomHandle;
    private WindowManager.LayoutParams capsuleLp;
    private WindowManager.LayoutParams handleLp;
    private BroadcastReceiver receiver;

    private int taskId = -1;
    private Rect bounds;
    private String label = "";
    private String pkg = "";
    private boolean mini;
    private Rect normalBounds;
    private int handleStartY;
    private Rect handleStartBounds;
    private boolean commandPending;
    private final Runnable sendBounds = () -> {
        commandPending = false;
        if (taskId <= 0 || bounds == null) return;
        Intent i = new Intent(SystemUiHook.ACTION_MOVE_TASK);
        i.setPackage(SUI_PKG);
        i.putExtra(SystemUiHook.EXTRA_TASK_ID, taskId);
        i.putExtra(SystemUiHook.EXTRA_BOUNDS, new Rect(bounds));
        sendBroadcast(i);
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        receiver = new Receiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(SystemUiHook.ACTION_TASK_CHANGED);
        filter.addAction(SystemUiHook.ACTION_TASK_VANISHED);
        registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            hide();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        startAsForeground();
        if (intent != null) {
            int id = intent.getIntExtra(EXTRA_TASK_ID, -1);
            if (id > 0) {
                taskId = id;
                handler.postDelayed(this::queryTask, 80);
            }
        }
        return START_STICKY;
    }

    private void queryTask() {
        if (taskId <= 0) return;
        Intent i = new Intent(SystemUiHook.ACTION_QUERY_TASK);
        i.setPackage(SUI_PKG);
        i.putExtra(SystemUiHook.EXTRA_TASK_ID, taskId);
        sendBroadcast(i);
    }

    private void startAsForeground() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    "小窗控件", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
            Notification n = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_manage)
                    .setContentTitle("类原生小窗")
                    .setContentText("正在显示系统级窗口控件")
                    .setOngoing(true)
                    .build();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Throwable t) {
            // 没有悬浮窗/前台服务权限时，chrome 仍尝试显示；失败只记日志。
            android.util.Log.w("NativeWindow/Chrome", "startForeground failed", t);
        }
    }

    @Override public void onDestroy() {
        hide();
        if (receiver != null) {
            try { unregisterReceiver(receiver); } catch (Throwable ignored) {}
            receiver = null;
        }
        super.onDestroy();
    }

    private void onTaskChanged(Intent intent) {
        int id = intent.getIntExtra(SystemUiHook.EXTRA_TASK_ID, -1);
        Rect b = intent.getParcelableExtra(SystemUiHook.EXTRA_BOUNDS);
        int mode = intent.getIntExtra(SystemUiHook.EXTRA_WINDOWING_MODE, 0);
        boolean visible = intent.getBooleanExtra(SystemUiHook.EXTRA_VISIBLE, true);
        if (id <= 0 || b == null) return;
        if (mode != 5 || !visible) {
            if (id == taskId) hide();
            return;
        }
        if (taskId != id) {
            hide();
            taskId = id;
        }
        label = intent.getStringExtra(SystemUiHook.EXTRA_LABEL);
        pkg = intent.getStringExtra(SystemUiHook.EXTRA_PACKAGE);
        bounds = new Rect(b);
        showOrUpdate();
    }

    private void showOrUpdate() {
        if (wm == null || bounds == null || taskId <= 0) return;
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予“显示在其他应用上层”", Toast.LENGTH_SHORT).show();
            return;
        }
        if (capsule == null) {
            capsule = new TaskCapsuleView(this, new CapsuleListener());
            capsule.bind(label, pkg);
            capsuleLp = baseLp();
            try {
                wm.addView(capsule, capsuleLp);
                capsule.animateIn();
            } catch (Throwable t) {
                android.util.Log.e("NativeWindow/Chrome", "add capsule failed", t);
                capsule = null;
                return;
            }
        } else {
            capsule.bind(label, pkg);
        }

        int topH = dp(mini ? 38 : 42);
        capsuleLp.width = mini
                ? Math.min(bounds.width(), dp(220))
                : bounds.width();
        capsuleLp.height = topH;
        capsuleLp.x = bounds.left;
        capsuleLp.y = bounds.top;
        safeUpdate(capsule, capsuleLp);

        if (mini || bounds.height() < dp(260)) {
            removeHandle();
        } else {
            if (bottomHandle == null) {
                bottomHandle = buildHandle();
                handleLp = baseLp();
                try {
                    wm.addView(bottomHandle, handleLp);
                } catch (Throwable t) {
                    android.util.Log.e("NativeWindow/Chrome", "add handle failed", t);
                    bottomHandle = null;
                }
            }
            if (bottomHandle != null) {
                handleLp.width = dp(88);
                handleLp.height = dp(22);
                handleLp.x = bounds.left + (bounds.width() - dp(88)) / 2;
                handleLp.y = bounds.bottom - dp(22);
                safeUpdate(bottomHandle, handleLp);
            }
        }
    }

    private WindowManager.LayoutParams baseLp() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setFitInsetsTypes(0);
        return lp;
    }

    private View buildHandle() {
        View v = new View(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x99FFFFFF);
        bg.setCornerRadius(dp(4));
        v.setBackground(bg);
        v.setOnTouchListener((view, ev) -> {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    handleStartY = (int) ev.getRawY();
                    handleStartBounds = bounds == null ? null : new Rect(bounds);
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (handleStartBounds == null) return true;
                    int dy = (int) ev.getRawY() - handleStartY;
                    int bottom = Math.max(handleStartBounds.top + dp(220),
                            handleStartBounds.bottom + dy);
                    Rect next = new Rect(handleStartBounds);
                    next.bottom = bottom;
                    bounds = next;
                    updateHandleLocal();
                    queueBounds();
                    return true;
                default:
                    return false;
            }
        });
        return v;
    }

    private void updateHandleLocal() {
        if (bottomHandle == null || handleLp == null || bounds == null) return;
        handleLp.y = bounds.bottom - dp(22);
        safeUpdate(bottomHandle, handleLp);
    }

    private void safeUpdate(View v, WindowManager.LayoutParams lp) {
        try {
            if (v != null && v.isAttachedToWindow()) wm.updateViewLayout(v, lp);
        } catch (Throwable t) {
            android.util.Log.w("NativeWindow/Chrome", "update layout failed", t);
        }
    }

    private void queueBounds() {
        if (commandPending) return;
        commandPending = true;
        handler.postDelayed(sendBounds, 16);
    }

    private void hide() {
        handler.removeCallbacks(sendBounds);
        if (capsule != null) {
            try { wm.removeView(capsule); } catch (Throwable ignored) {}
            capsule = null;
        }
        removeHandle();
        bounds = null;
        taskId = -1;
    }

    private void removeHandle() {
        if (bottomHandle != null) {
            try { wm.removeView(bottomHandle); } catch (Throwable ignored) {}
            bottomHandle = null;
        }
    }

    private final class CapsuleListener implements TaskCapsuleView.Listener {
        @Override public void onMove(float dx, float dy) {
            if (bounds == null) return;
            bounds.offset((int) dx, (int) dy);
            capsuleLp.x = bounds.left;
            capsuleLp.y = bounds.top;
            safeUpdate(capsule, capsuleLp);
            if (bottomHandle != null) {
                handleLp.x = bounds.left + (bounds.width() - dp(88)) / 2;
                handleLp.y = bounds.bottom - dp(22);
                safeUpdate(bottomHandle, handleLp);
            }
        }
        @Override public void onMoveEnd() { queueBounds(); }
        @Override public void onMini() {
            if (!mini) {
                normalBounds = bounds == null ? null : new Rect(bounds);
                mini = true;
                Intent i = new Intent(SystemUiHook.ACTION_MINI_TASK);
                i.setPackage(SUI_PKG);
                i.putExtra(SystemUiHook.EXTRA_TASK_ID, taskId);
                sendBroadcast(i);
            } else {
                mini = false;
                if (normalBounds != null) {
                    bounds = new Rect(normalBounds);
                    Intent i = new Intent(SystemUiHook.ACTION_MOVE_TASK);
                    i.setPackage(SUI_PKG);
                    i.putExtra(SystemUiHook.EXTRA_TASK_ID, taskId);
                    i.putExtra(SystemUiHook.EXTRA_BOUNDS, new Rect(bounds));
                    sendBroadcast(i);
                    showOrUpdate();
                }
            }
        }
        @Override public void onFullscreen() {
            Intent i = new Intent(SystemUiHook.ACTION_FULLSCREEN_TASK);
            i.setPackage(SUI_PKG);
            i.putExtra(SystemUiHook.EXTRA_TASK_ID, taskId);
            sendBroadcast(i);
            hide();
        }
        @Override public void onClose() {
            Intent i = new Intent(SystemUiHook.ACTION_CLOSE_TASK);
            i.setPackage(SUI_PKG);
            i.putExtra(SystemUiHook.EXTRA_TASK_ID, taskId);
            sendBroadcast(i);
            hide();
        }
    }

    private final class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            if (SystemUiHook.ACTION_TASK_VANISHED.equals(intent.getAction())) {
                int id = intent.getIntExtra(SystemUiHook.EXTRA_TASK_ID, -1);
                if (id == taskId) hide();
                return;
            }
            if (SystemUiHook.ACTION_TASK_CHANGED.equals(intent.getAction())) {
                onTaskChanged(intent);
            }
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
