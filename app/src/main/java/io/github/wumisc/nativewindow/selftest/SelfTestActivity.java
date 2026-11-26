package io.github.wumisc.nativewindow.selftest;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.github.wumisc.nativewindow.Palette;

/**
 * 打开即自动跑的自检页：不需要用户点任何按钮。
 *
 * 它不假设自己“已经成功”，而是把当前任务真的交给 SystemUI 的 WCT 改成 freeform，
 * 然后观察本 Activity 的窗口尺寸是否变小；再恢复全屏。这样能一次验证：
 * - SystemServer 的 freeform 门禁是否打开；
 * - SystemUI 的 ShellTaskOrganizer / WindowContainerTransaction 是否生效；
 * - 任务是否真的以 FREEFORM 运行，而不是只把 Activity 提到前台。
 *
 * 胶囊是否挂上不在这里直接读 View（跨进程），由日志里的
 * “NativeWindow/Caption: capsule attached”另行确认。
 */
public class SelfTestActivity extends Activity {
    private static final String ACTION_FORCE_WINDOW =
            "io.github.wumisc.nativewindow.action.FORCE_SMALL_WINDOW";
    private static final String EXTRA_TASK_ID = "task_id";
    private static final String EXTRA_BOUNDS = "bounds";
    private static final String EXTRA_MODE = "mode";
    private static final String EXTRA_SELF_TEST = "self_test";
    private static final int MODE_FREEFORM = 5;
    private static final int MODE_FULLSCREEN = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Row> rows = new ArrayList<>();
    private LinearLayout list;
    private TextView status;
    private TextView summary;
    private Rect screen;
    private Rect freeformBounds;
    private int taskId = -1;
    private long startedAt;
    private boolean freeformSeen;
    private boolean hyper;
    private boolean hyperProbeOk;
    private String hyperProbeDetail = "";
    private boolean finished;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyBars();
        buildUi();
        startedAt = System.currentTimeMillis();
        taskId = getTaskId();
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            screen = new Rect(wm.getMaximumWindowMetrics().getBounds());
        } catch (Throwable t) {
            screen = new Rect(0, 0, 1080, 2400);
        }

        add("设备", true, Build.MANUFACTURER + " " + Build.MODEL
                + " · Android " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT);
        add("构建", Build.VERSION.SDK_INT >= 37,
                Build.DISPLAY + " · target " + getApplicationInfo().targetSdkVersion);
        add("任务", taskId > 0, "taskId=" + taskId);
        add("悬浮窗权限", Settings.canDrawOverlays(this), "仅预览需要；真实小窗不需要");
        add("屏幕", true, screen.width() + " × " + screen.height());
        hyper = HyperOs.isHyperOs();
        add("ROM", true, hyper ? HyperOs.describe() : Build.DISPLAY + " · AOSP WMShell");

        if (hyper) {
            status.setText("HyperOS 4：正在用 MIUI freeform options 拉起探针…");
            main.postDelayed(this::requestHyperProbe, 700);
        } else {
            status.setText("正在请求 freeform…");
            main.postDelayed(this::requestFreeform, 700);
            main.postDelayed(this::evaluate, 4800);
        }
    }

    private void applyBars() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        boolean night = Palette.isNight(this);
        getWindow().getDecorView().setSystemUiVisibility(night ? 0
                : (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Palette.bg(this));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(48), dp(20), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("自动自检");
        title.setTextColor(Palette.fg(this));
        title.setTextSize(27);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);

        status = new TextView(this);
        status.setText("准备中…");
        status.setTextColor(Palette.secondary(this));
        status.setTextSize(13);
        status.setPadding(0, dp(6), 0, dp(18));
        root.addView(status);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        summary = new TextView(this);
        summary.setTextColor(Palette.fg(this));
        summary.setTextSize(13);
        summary.setLineSpacing(dp(4), 1f);
        summary.setPadding(0, dp(18), 0, dp(10));
        root.addView(summary);

        Button again = new Button(this);
        again.setText("重新自检");
        again.setAllCaps(false);
        again.setTextColor(Palette.buttonText(this));
        again.setBackground(round(Palette.button(this), 18, Palette.buttonStroke(this)));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        alp.topMargin = dp(8);
        root.addView(again, alp);
        again.setOnClickListener(v -> {
            if (finished) {
                recreate();
            }
        });

        Button back = new Button(this);
        back.setText("进入主界面");
        back.setAllCaps(false);
        back.setTextColor(Palette.secondary(this));
        back.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        root.addView(back, blp);
        back.setOnClickListener(v -> finish());

        setContentView(scroll);
    }

    private void requestFreeform() {
        if (taskId <= 0) {
            status.setText("拿不到 taskId，无法自检");
            add("freeform", false, "taskId <= 0");
            finishReport();
            return;
        }
        int w = Math.max(dp(320), (int) (screen.width() * 0.78f));
        int h = Math.max(dp(420), (int) (screen.height() * 0.62f));
        int left = Math.max(0, (screen.width() - w) / 2);
        int top = Math.max(0, (screen.height() - h) / 2);
        Rect target = new Rect(left, top, left + w, top + h);
        sendForce(target, MODE_FREEFORM);
        add("freeform 请求", true, "target=" + target.flattenToString());
        status.setText("已请求 freeform，等待任务尺寸变化…");
    }

    /** HyperOS 4：不假设 AOSP WCT 可用，直接用 MIUI 的 ActivityOptions 拉起独立探针。 */
    private void requestHyperProbe() {
        try {
            SharedPreferences p = getSharedPreferences(ProbeActivity.PREFS, Context.MODE_PRIVATE);
            p.edit().clear().apply();
        } catch (Throwable ignored) {}
        boolean launched = HyperOsFreeform.launchProbe(this);
        add("HyperOS 小窗启动", launched,
                launched ? "ActivityOptions: FREEFORM 5 + MIUI flag 2 + getFreeformRect"
                        : "startActivity 抛异常，见探针记录");
        status.setText(launched ? "探针已拉起，等待它测量任务尺寸…" : "探针启动失败");
        main.postDelayed(this::evaluateHyperProbe, 3600);
    }

    private void evaluateHyperProbe() {
        if (finished) return;
        try {
            SharedPreferences p = getSharedPreferences(ProbeActivity.PREFS, Context.MODE_PRIVATE);
            long time = p.getLong("time", 0);
            hyperProbeOk = p.getBoolean(ProbeActivity.KEY_OK, false);
            hyperProbeDetail = p.getString(ProbeActivity.KEY_DETAIL, "no probe result");
            if (time <= 0) {
                add("探针结果", false, "没有收到 ProbeActivity 的测量结果；ActivityOptions 可能被 HyperOS 拒绝");
            } else {
                add("探针结果", hyperProbeOk, hyperProbeDetail);
            }
        } catch (Throwable t) {
            hyperProbeDetail = "read failed: " + t;
            add("探针结果", false, hyperProbeDetail);
        }
        status.setText(hyperProbeOk ? "HyperOS 小窗任务已确认" : "HyperOS 小窗未生效");
        main.postDelayed(this::finishReport, 1000);
    }

    private void sendForce(Rect bounds, int mode) {
        try {
            Intent i = new Intent(ACTION_FORCE_WINDOW);
            i.setPackage("com.android.systemui");
            i.putExtra(EXTRA_TASK_ID, taskId);
            if (bounds != null) i.putExtra(EXTRA_BOUNDS, bounds);
            i.putExtra(EXTRA_MODE, mode);
            i.putExtra(EXTRA_SELF_TEST, true);
            sendBroadcast(i);
        } catch (Throwable t) {
            add("发送请求", false, String.valueOf(t));
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (hyper) return;
        Rect now = currentBounds(newConfig);
        if (finished || freeformSeen || now == null || screen == null) return;
        boolean smaller = now.width() < screen.width() - dp(80)
                || now.height() < screen.height() - dp(80);
        if (smaller) {
            freeformSeen = true;
            freeformBounds = new Rect(now);
            add("任务尺寸", true, now.width() + " × " + now.height()
                    + "（屏幕 " + screen.width() + " × " + screen.height() + "）");
            status.setText("任务已以小窗运行，2 秒后恢复全屏…");
            main.postDelayed(this::restoreFullscreen, 2000);
        }
    }

    private Rect currentBounds(Configuration config) {
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            return new Rect(wm.getCurrentWindowMetrics().getBounds());
        } catch (Throwable t) {
            return null;
        }
    }

    private void evaluate() {
        if (finished) return;
        if (!freeformSeen) {
            Rect now = currentBounds(getResources().getConfiguration());
            if (now != null && screen != null
                    && (now.width() < screen.width() - dp(80)
                    || now.height() < screen.height() - dp(80))) {
                freeformSeen = true;
                freeformBounds = new Rect(now);
                add("任务尺寸", true, now.width() + " × " + now.height()
                        + "（屏幕 " + screen.width() + " × " + screen.height() + "）");
                status.setText("任务已以小窗运行，恢复全屏…");
                restoreFullscreen();
                return;
            }
            add("任务尺寸", false, "4.8 秒内没有变小；当前="
                    + (now == null ? "unknown" : now.width() + "×" + now.height()));
            status.setText("freeform 没有生效");
            restoreFullscreen();
            return;
        }
    }

    private void restoreFullscreen() {
        if (finished) return;
        sendForce(null, MODE_FULLSCREEN);
        status.setText("已请求恢复全屏…");
        main.postDelayed(this::finishReport, 1500);
    }

    private void finishReport() {
        if (finished) return;
        finished = true;
        int passed = 0;
        for (Row row : rows) if (row.pass) passed++;
        boolean core = hyper ? hyperProbeOk : freeformSeen;
        summary.setText((core ? "核心链路通过" : "核心链路未通过")
                + "：" + passed + "/" + rows.size() + " 项通过\n"
                + "耗时 " + ((System.currentTimeMillis() - startedAt) / 1000) + "s · "
                + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "\n\n"
                + (core
                ? (hyper
                ? "HyperOS 4 已经接受 MIUI freeform ActivityOptions，独立探针任务确实变小。"
                : "真实小窗已经由 SystemUI 的 WindowContainerTransaction 改成了 freeform。")
                : (hyper
                ? "HyperOS 4 上探针仍全屏/没起来。先确认系统框架作用域，再看日志里 "
                + "MiuiMultiWindowUtils.supportFreeform 是否被放行；这不是 PixelOS 的 WCT 路径。"
                : "如果任务始终全屏：先确认 LSPosed 勾选了系统框架、SystemUI、Pixel/LOS 桌面，"
                + "再重启 SystemUI；仍失败请用主界面的“收集并分享日志”。")));
        status.setText(core ? "自检完成：核心通过" : "自检完成：核心失败");
    }

    private void add(String label, boolean pass, String detail) {
        rows.add(new Row(label, pass, detail));
        if (list == null) return;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));

        TextView mark = new TextView(this);
        mark.setText(pass ? "✓" : "✕");
        mark.setTextSize(16);
        mark.setTextColor(pass ? 0xFF34C759 : Palette.danger(this));
        mark.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        mark.setGravity(Gravity.CENTER);
        row.addView(mark, new LinearLayout.LayoutParams(dp(28), dp(28)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(Palette.fg(this));
        name.setTextSize(14);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        text.addView(name);
        TextView desc = new TextView(this);
        desc.setText(detail);
        desc.setTextColor(Palette.secondary(this));
        desc.setTextSize(11);
        desc.setPadding(0, dp(2), 0, 0);
        text.addView(desc);
        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        list.addView(row);
    }

    private GradientDrawable round(int color, int radiusDp, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) d.setStroke(dp(1), strokeColor);
        return d;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static final class Row {
        final String label;
        final boolean pass;
        final String detail;
        Row(String label, boolean pass, String detail) {
            this.label = label;
            this.pass = pass;
            this.detail = detail;
        }
    }
}
