package io.github.wumisc.nativewindow.hook;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 画在 AOSP CaptionWindowDecoration 里的 HyperOS 胶囊。
 *
 * 关键：它不是应用悬浮窗，而是原生 caption 根 View 的子 View。
 * 因此它天然跟 Task surface 一起移动/缩放/裁剪，不会出现“胶囊先动、窗口后动”的两张皮。
 *
 * 拖动交给原生 caption 的 OnTouchListener，本 View 只负责按钮和视觉。
 */
final class NativeCapsuleView extends FrameLayout {
    interface Listener {
        void onMini();
        void onFullscreen();
        void onClose();
    }

    private final Listener listener;
    private final ImageView icon;
    private final TextView title;
    private final boolean night;

    NativeCapsuleView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        int nightMode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        night = nightMode == Configuration.UI_MODE_NIGHT_YES;
        setClipChildren(false);
        setClipToPadding(false);
        setBackground(round(night ? 0xF21C1C1E : 0xF2FFFFFF,
                22, night ? 0x33FFFFFF : 0x1A000000));
        setPadding(dp(10), dp(3), dp(6), dp(3));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        row.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));

        title = new TextView(context);
        title.setTextColor(night ? 0xFFF2F2F7 : 0xFF17171A);
        title.setTextSize(12);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(dp(8), 0, dp(8), 0);
        row.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        row.addView(button(context, "—", v -> listener.onMini()));
        row.addView(button(context, "⛶", v -> listener.onFullscreen()));
        row.addView(button(context, "×", v -> listener.onClose()));
    }

    void bind(String label, String pkg) {
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
        v.setTextColor(night ? 0xFFE5E5EA : 0xFF3A3A3C);
        v.setTextSize(14);
        v.setGravity(Gravity.CENTER);
        v.setBackground(round(night ? 0x22FFFFFF : 0x11000000, 13, 0));
        v.setPadding(dp(7), 0, dp(7), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(26));
        lp.leftMargin = dp(4);
        v.setLayoutParams(lp);
        v.setOnClickListener(click);
        return v;
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
