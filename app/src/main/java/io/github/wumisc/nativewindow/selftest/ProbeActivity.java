package io.github.wumisc.nativewindow.selftest;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.wumisc.nativewindow.Palette;

/** 被 HyperOS 的 ActivityOptions 拉起的探针；只负责测量自己是不是小窗。 */
public class ProbeActivity extends Activity {
    static final String PREFS = "hyper_probe";
    static final String KEY_OK = "ok";
    static final String KEY_DETAIL = "detail";
    static final String KEY_BOUNDS = "bounds";
    static final String KEY_BUILD = "build";
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Palette.bg(this));
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        TextView title = new TextView(this);
        title.setText("HyperOS freeform probe");
        title.setTextSize(18);
        title.setTextColor(Palette.fg(this));
        title.setGravity(Gravity.CENTER);
        root.addView(title);
        TextView sub = new TextView(this);
        sub.setText("正在测量任务尺寸…");
        sub.setTextSize(12);
        sub.setTextColor(Palette.secondary(this));
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(8), 0, 0);
        root.addView(sub);
        setContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        main.postDelayed(() -> measure(sub), 1100);
    }

    private void measure(TextView sub) {
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            Rect cur = new Rect(wm.getCurrentWindowMetrics().getBounds());
            Rect max = new Rect(wm.getMaximumWindowMetrics().getBounds());
            boolean ok = cur.width() < max.width() - dp(60)
                    || cur.height() < max.height() - dp(60);
            String detail = "current=" + cur.width() + "×" + cur.height()
                    + " max=" + max.width() + "×" + max.height();
            write(this, ok, detail, cur);
            sub.setText(ok ? "PASS · 小窗任务" : "FAIL · 仍是全屏");
            sub.setTextColor(ok ? 0xFF34C759 : Palette.danger(this));
        } catch (Throwable t) {
            write(this, false, "measure failed: " + t, null);
            sub.setText("FAIL · " + t);
            sub.setTextColor(Palette.danger(this));
        }
        main.postDelayed(this::finish, 1400);
    }

    static void writeBuild(Context context, String detail) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_BUILD, detail).apply();
    }

    static void write(Context context, boolean ok, String detail, Rect bounds) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit()
                .putBoolean(KEY_OK, ok)
                .putString(KEY_DETAIL, detail)
                .putString(KEY_BOUNDS, bounds == null ? "" : bounds.flattenToString())
                .putLong("time", System.currentTimeMillis())
                .apply();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
