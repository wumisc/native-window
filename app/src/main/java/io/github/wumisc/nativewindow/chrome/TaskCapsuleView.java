package io.github.wumisc.nativewindow.chrome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.wumisc.nativewindow.anim.SpringAnimator;

/**
 * HyperOS 3 风格的窗口胶囊：图标 + 标题 + 最小化 / 全屏 / 关闭。
 *
 * 这不是“预览卡片”，它是贴在真实 freeform Task bounds 上的一条系统 chrome；
 * 拖动只移动 chrome，并把新 bounds 交给 SystemUI 的 WCT 去真正移动任务。
 */
public final class TaskCapsuleView extends FrameLayout {
    public interface Listener {
        void onMove(float dx, float dy);
        void onMoveEnd();
        void onMini();
        void onFullscreen();
        void onClose();
    }

    private final Listener listener;
    private final int touchSlop;
    private float downX, downY;
    private float lastX, lastY;
    private boolean dragging;
    private ImageView icon;
    private TextView title;

    public TaskCapsuleView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setClipChildren(false);
        setClipToPadding(false);
        setElevation(dp(10));
        setBackground(round(0xF21C1C1E, 24, 0x33FFFFFF));
        setPadding(dp(10), dp(4), dp(8), dp(4));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        row.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));

        title = new TextView(context);
        title.setTextColor(0xFFF2F2F7);
        title.setTextSize(12);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(dp(8), 0, dp(8), 0);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        row.addView(title, titleLp);

        row.addView(button(context, "—", v -> listener.onMini()));
        row.addView(button(context, "⛶", v -> listener.onFullscreen()));
        row.addView(button(context, "×", v -> listener.onClose()));

        setAlpha(0f);
        setScaleX(0.92f);
        setScaleY(0.92f);
    }

    public void animateIn() {
        SpringAnimator.windowOpen(v -> {
            setAlpha(v);
            setScaleX(0.92f + 0.08f * v);
            setScaleY(0.92f + 0.08f * v);
        }).start(0f, 1f);
    }

    public void bind(String label, String pkg) {
        title.setText(label == null || label.isEmpty() ? "小窗" : label);
        try {
            PackageManager pm = getContext().getPackageManager();
            icon.setImageDrawable(pm.getApplicationIcon(pkg));
        } catch (Throwable t) {
            icon.setImageResource(android.R.drawable.ic_menu_manage);
        }
    }

    private TextView button(Context context, String text, OnClickListener click) {
        TextView v = new TextView(context);
        v.setText(text);
        v.setTextColor(0xFFE5E5EA);
        v.setTextSize(14);
        v.setGravity(Gravity.CENTER);
        v.setBackground(round(0x22FFFFFF, 14, 0));
        v.setPadding(dp(8), 0, dp(8), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(32), dp(28));
        lp.leftMargin = dp(4);
        v.setLayoutParams(lp);
        v.setOnClickListener(click);
        return v;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getRawX();
                downY = ev.getRawY();
                lastX = downX;
                lastY = downY;
                dragging = false;
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!dragging
                        && (Math.abs(ev.getRawX() - downX) > touchSlop
                        || Math.abs(ev.getRawY() - downY) > touchSlop)) {
                    dragging = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getRawX();
                downY = ev.getRawY();
                lastX = downX;
                lastY = downY;
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float x = ev.getRawX();
                float y = ev.getRawY();
                if (!dragging) {
                    if (Math.abs(x - downX) > touchSlop || Math.abs(y - downY) > touchSlop) {
                        dragging = true;
                    } else {
                        return true;
                    }
                }
                listener.onMove(x - lastX, y - lastY);
                lastX = x;
                lastY = y;
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) listener.onMoveEnd();
                dragging = false;
                return true;
            default:
                return super.onTouchEvent(ev);
        }
    }

    private GradientDrawable round(int color, int radiusDp, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
