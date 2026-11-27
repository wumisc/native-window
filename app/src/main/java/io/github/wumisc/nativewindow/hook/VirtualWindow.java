package io.github.wumisc.nativewindow.hook;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 一块“米窗”式悬浮卡片：虚拟屏承载真实 Activity，TextureView 显示画面，
 * 顶部标题栏拖动、右下角拖动缩放、底栏返回/桌面/全屏/关闭。
 */
final class VirtualWindow {
    private static final String TAG = "NativeWindow/VW";

    private final Context context;
    private final WindowManager windowManager;
    private final DisplayManager displayManager;
    private final Object activityTaskManager;
    private final ComponentName component;
    private final int userId;
    private final VirtualWindowInput input = new VirtualWindowInput();

    private final FrameLayout root;
    private final LinearLayout card;
    private final LinearLayout header;
    private final FrameLayout contentWrap;
    private final TextureView texture;
    private final ImageView icon;
    private final TextView title;
    private final LinearLayout footer;
    private final WindowManager.LayoutParams params;

    private VirtualDisplay virtualDisplay;
    private int displayId = -1;
    private int taskId;
    private int contentWidth;
    private int contentHeight;
    private int originalWidth;
    private int originalHeight;
    private int densityDpi;
    private int screenWidth;
    private int screenHeight;
    private boolean closed;
    private boolean mini;

    private final int dp10;
    private final int dp36;
    private final int dp30;
    private final int cardColor;
    private final int fgColor;
    private final int subColor;

    private long lastHeaderTap;
    private float headerTapX;
    private float headerTapY;

    VirtualWindow(Context context, WindowManager windowManager, DisplayManager displayManager,
                  Object activityTaskManager, ComponentName component, int taskId, int userId) {
        this.context = context;
        this.windowManager = windowManager;
        this.displayManager = displayManager;
        this.activityTaskManager = activityTaskManager;
        this.component = component;
        this.taskId = taskId;
        this.userId = userId;

        float d = context.getResources().getDisplayMetrics().density;
        dp10 = Math.round(10 * d);
        dp36 = Math.round(36 * d);
        dp30 = Math.round(30 * d);
        densityDpi = context.getResources().getDisplayMetrics().densityDpi;

        Display main = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        main.getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;

        boolean night = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        cardColor = night ? 0xFF1C1D21 : 0xFFF7F8FA;
        fgColor = night ? 0xFFF2F3F5 : 0xFF1B1C1E;
        subColor = night ? 0xFFB8BAC0 : 0xFF70737A;

        originalWidth = contentWidth = Math.max(Math.round(screenWidth * 0.82f), dp(300));
        originalHeight = contentHeight = Math.max(Math.round(screenHeight * 0.56f), dp(420));

        root = new FrameLayout(context);
        root.setPadding(dp10, dp10, dp10, dp10);
        root.setClipChildren(false);
        root.setClipToPadding(false);

        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(cardColor, 18));
        card.setElevation(dp(12));
        card.setClipChildren(false);
        root.addView(card, new FrameLayout.LayoutParams(
                contentWidth, dp36 + contentHeight + dp36));

        header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(10), 0, dp(6), 0);
        card.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp36));

        icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        header.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));

        title = new TextView(context);
        title.setTextColor(fgColor);
        title.setTextSize(13);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(dp(7), 0, dp(6), 0);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(context);
        close.setText("×");
        close.setTextColor(subColor);
        close.setTextSize(19);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("关闭小窗");
        close.setOnClickListener(v -> close());
        header.addView(close, new LinearLayout.LayoutParams(dp30, dp30));

        contentWrap = new FrameLayout(context);
        contentWrap.setClipToOutline(true);
        contentWrap.setBackground(round(0xFF000000, 12));
        card.addView(contentWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, contentHeight));

        texture = new TextureView(context);
        texture.setOpaque(true);
        contentWrap.addView(texture, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View resize = new TextView(context);
        ((TextView) resize).setText("◢");
        ((TextView) resize).setTextColor(0xCCFFFFFF);
        ((TextView) resize).setTextSize(16);
        ((TextView) resize).setGravity(Gravity.CENTER);
        resize.setBackgroundColor(0x33000000);
        FrameLayout.LayoutParams resizeLp = new FrameLayout.LayoutParams(dp30, dp30);
        resizeLp.gravity = Gravity.BOTTOM | Gravity.END;
        contentWrap.addView(resize, resizeLp);
        resize.setOnTouchListener(new ResizeTouch());

        footer = new LinearLayout(context);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, 0, 0, 0);
        card.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp36));

        addFooterButton("返回", () -> input.key(KeyEvent.KEYCODE_BACK, displayId));
        addFooterButton("桌面", () -> input.key(KeyEvent.KEYCODE_HOME, displayId));
        addFooterButton("全屏", this::fullscreen);
        addFooterButton("关闭", this::close);

        header.setOnTouchListener(new HeaderTouch());

        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                attachSurface(st, w, h);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                attachSurface(st, w, h);
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                if (virtualDisplay != null) virtualDisplay.setSurface(null);
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
        });
        texture.setOnTouchListener((v, event) -> {
            if (displayId > 0) input.motion(event, displayId);
            return true;
        });

        params = new WindowManager.LayoutParams(
                contentWidth + dp10 * 2, dp36 + contentHeight + dp36 + dp10 * 2,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = Math.max(0, (screenWidth - params.width) / 2);
        params.y = Math.max(dp(40), Math.round(screenHeight * 0.16f));
    }

    int displayId() { return displayId; }

    void show() {
        if (virtualDisplay == null) {
            String name = "nativewindow-" + SystemClock.uptimeMillis();
            virtualDisplay = displayManager.createVirtualDisplay(
                    name, contentWidth, contentHeight, densityDpi, null, 1668);
            displayId = virtualDisplay.getDisplay().getDisplayId();
            XposedBridge.log(TAG + ": virtualDisplay=" + displayId
                    + " size=" + contentWidth + "x" + contentHeight + " dpi=" + densityDpi);
        }
        windowManager.addView(root, params);
        launchTarget();
        refreshTaskInfo(0);
        startWatchdog();
    }

    private void attachSurface(SurfaceTexture st, int w, int h) {
        if (virtualDisplay == null) return;
        try {
            st.setDefaultBufferSize(w, h);
            virtualDisplay.setSurface(new Surface(st));
            virtualDisplay.resize(w, h, densityDpi);
            XposedBridge.log(TAG + ": surface " + w + "x" + h + " -> display " + displayId);
        } catch (Throwable t) {
            Log.e(TAG, "attachSurface failed", t);
        }
    }

    private void launchTarget() {
        try {
            if (taskId > 0) {
                moveTaskToDisplay(taskId, displayId);
                call("moveTaskToFront", new Class[]{int.class, int.class}, taskId, 0);
                XposedBridge.log(TAG + ": moved task " + taskId + " to " + displayId);
                return;
            }
            if (component == null) {
                Log.w(TAG, "no component and no task");
                return;
            }
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setComponent(component);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(displayId);
            options.setLaunchBounds(new Rect(0, 0, contentWidth, contentHeight));
            Object user = android.os.UserHandle.getUserHandleForUid(1000);
            XposedHelpers.callMethod(context, "startActivityAsUser",
                    intent, options.toBundle(), user);
            XposedBridge.log(TAG + ": started " + component.flattenToShortString()
                    + " on display " + displayId);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": launch failed " + t);
            Log.e(TAG, "launch failed", t);
            try {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.addCategory(Intent.CATEGORY_LAUNCHER);
                intent.setComponent(component);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setLaunchDisplayId(displayId);
                context.startActivity(intent, options.toBundle());
            } catch (Throwable t2) {
                Log.e(TAG, "fallback launch failed", t2);
            }
        }
    }

    private void refreshTaskInfo(int attempt) {
        if (closed) return;
        try {
            Object tasks = call("getAllRootTaskInfosOnDisplay",
                    new Class[]{int.class}, displayId);
            if (tasks instanceof List && !((List<?>) tasks).isEmpty()) {
                Object info = ((List<?>) tasks).get(0);
                int id = intField(info, "taskId");
                if (id > 0) {
                    taskId = id;
                    updateLabelAndIcon();
                    return;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "refreshTaskInfo failed: " + t);
        }
        if (attempt < 12) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> refreshTaskInfo(attempt + 1), 250);
        }
    }

    private void updateLabelAndIcon() {
        String label = null;
        Bitmap bitmap = null;
        try {
            Object desc = call("getTaskDescription", new Class[]{int.class}, taskId);
            if (desc != null) {
                Object l = desc.getClass().getMethod("getLabel").invoke(desc);
                if (l instanceof CharSequence) label = l.toString();
                Object b = desc.getClass().getMethod("getIcon").invoke(desc);
                if (b instanceof Bitmap) bitmap = (Bitmap) b;
            }
        } catch (Throwable ignored) {}
        if (label == null && component != null) {
            try {
                PackageManager pm = context.getPackageManager();
                label = pm.getApplicationLabel(pm.getApplicationInfo(component.getPackageName(), 0))
                        .toString();
            } catch (Throwable ignored) {}
        }
        title.setText(label == null ? "应用" : label);
        if (bitmap != null) {
            icon.setImageBitmap(bitmap);
        } else if (component != null) {
            try {
                icon.setImageDrawable(context.getPackageManager()
                        .getApplicationIcon(component.getPackageName()));
            } catch (Throwable ignored) {}
        }
    }

    private void startWatchdog() {
        Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (closed) return;
                try {
                    Object tasks = call("getAllRootTaskInfosOnDisplay",
                            new Class[]{int.class}, displayId);
                    if (taskId > 0 && tasks instanceof List && ((List<?>) tasks).isEmpty()) {
                        XposedBridge.log(TAG + ": task gone, close display " + displayId);
                        close();
                        return;
                    }
                } catch (Throwable ignored) {}
                h.postDelayed(this, 1200);
            }
        }, 2500);
    }

    private void addFooterButton(String text, Runnable action) {
        TextView b = new TextView(context);
        b.setText(text);
        b.setTextColor(subColor);
        b.setTextSize(12);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setOnClickListener(v -> action.run());
        footer.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
    }

    private void moveWindow(int dx, int dy, int startX, int startY) {
        params.x = clamp(startX + dx, -dp10, screenWidth - params.width + dp10);
        params.y = clamp(startY + dy, -dp10, screenHeight - params.height + dp10);
        try {
            windowManager.updateViewLayout(root, params);
        } catch (Throwable ignored) {}
    }

    private void resizeWindow(int dw, int dh, int startW, int startH) {
        contentWidth = clamp(startW + dw, dp(260), screenWidth - dp10 * 2);
        contentHeight = clamp(startH + dh, dp(320), screenHeight - dp36 * 2 - dp10 * 2);
        card.getLayoutParams().width = contentWidth;
        card.getLayoutParams().height = dp36 + contentHeight + dp36;
        contentWrap.getLayoutParams().height = contentHeight;
        params.width = contentWidth + dp10 * 2;
        params.height = dp36 + contentHeight + dp36 + dp10 * 2;
        card.requestLayout();
        contentWrap.requestLayout();
        try {
            windowManager.updateViewLayout(root, params);
        } catch (Throwable ignored) {}
    }

    private void toggleMini() {
        mini = !mini;
        int w = mini ? Math.max(dp(220), originalWidth / 2) : originalWidth;
        int h = mini ? Math.max(dp(300), originalHeight / 2) : originalHeight;
        contentWidth = w;
        contentHeight = h;
        card.getLayoutParams().width = w;
        card.getLayoutParams().height = dp36 + h + dp36;
        contentWrap.getLayoutParams().height = h;
        params.width = w + dp10 * 2;
        params.height = dp36 + h + dp36 + dp10 * 2;
        card.requestLayout();
        try {
            windowManager.updateViewLayout(root, params);
        } catch (Throwable ignored) {}
    }

    private void fullscreen() {
        boolean moved = false;
        try {
            if (taskId > 0) {
                moveTaskToDisplay(taskId, Display.DEFAULT_DISPLAY);
                call("moveTaskToFront", new Class[]{int.class, int.class}, taskId, 0);
                moved = true;
            }
        } catch (Throwable t) {
            Log.e(TAG, "fullscreen failed", t);
        }
        close(!moved);
    }

    void close() {
        close(true);
    }

    private void close(boolean removeTask) {
        if (closed) return;
        closed = true;
        try {
            if (removeTask && taskId > 0) call("removeTask", new Class[]{int.class}, taskId);
        } catch (Throwable ignored) {}
        try {
            if (virtualDisplay != null) virtualDisplay.release();
        } catch (Throwable ignored) {}
        try {
            windowManager.removeView(root);
        } catch (Throwable ignored) {}
        VirtualWindowEngine.remove(this);
        XposedBridge.log(TAG + ": closed display " + displayId);
    }

    private void moveTaskToDisplay(int id, int targetDisplay) throws Exception {
        call("moveRootTaskToDisplay", new Class[]{int.class, int.class}, id, targetDisplay);
    }

    private Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = null;
        Class<?> c = activityTaskManager.getClass();
        while (c != null && m == null) {
            try {
                m = c.getDeclaredMethod(name, types);
            } catch (NoSuchMethodException ignored) {
                c = c.getSuperclass();
            }
        }
        if (m == null) {
            for (Method candidate : activityTaskManager.getClass().getMethods()) {
                if (candidate.getName().equals(name)
                        && candidate.getParameterTypes().length == types.length) {
                    m = candidate;
                    break;
                }
            }
        }
        if (m == null) throw new NoSuchMethodException(name);
        m.setAccessible(true);
        return m.invoke(activityTaskManager, args);
    }

    private static int intField(Object o, String name) {
        try {
            java.lang.reflect.Field f = o.getClass().getField(name);
            return f.getInt(o);
        } catch (Throwable t) {
            return -1;
        }
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private final class HeaderTouch implements View.OnTouchListener {
        private float downRawX, downRawY;
        private int startX, startY;

        @Override public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    startX = params.x;
                    startY = params.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    moveWindow((int) (event.getRawX() - downRawX),
                            (int) (event.getRawY() - downRawY), startX, startY);
                    return true;
                case MotionEvent.ACTION_UP:
                    long now = SystemClock.uptimeMillis();
                    float dx = event.getRawX() - headerTapX;
                    float dy = event.getRawY() - headerTapY;
                    if (now - lastHeaderTap < 320 && Math.abs(dx) < dp(16)
                            && Math.abs(dy) < dp(16)) {
                        toggleMini();
                        lastHeaderTap = 0;
                    } else {
                        lastHeaderTap = now;
                        headerTapX = event.getRawX();
                        headerTapY = event.getRawY();
                    }
                    return true;
            }
            return false;
        }
    }

    private final class ResizeTouch implements View.OnTouchListener {
        private float downRawX, downRawY;
        private int startW, startH;

        @Override public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    startW = contentWidth;
                    startH = contentHeight;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    resizeWindow((int) (event.getRawX() - downRawX),
                            (int) (event.getRawY() - downRawY), startW, startH);
                    return true;
                case MotionEvent.ACTION_UP:
                    return true;
            }
            return false;
        }
    }
}
