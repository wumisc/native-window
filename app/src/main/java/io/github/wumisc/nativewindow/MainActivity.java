package io.github.wumisc.nativewindow;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import io.github.wumisc.nativewindow.log.LogCollector;

public class MainActivity extends Activity {
    private static final int REQ_OVERLAY = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        boolean night = Palette.isNight(this);
        getWindow().getDecorView().setSystemUiVisibility(night ? 0
                : (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
        try {
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.setSystemBarsAppearance(night ? 0
                        : (android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS),
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } catch (Throwable ignored) {}

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Palette.bg(this));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(56), dp(24), dp(40));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("类原生小窗");
        title.setTextColor(Palette.fg(this));
        title.setTextSize(30);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView version = new TextView(this);
        version.setText("0.7.0 HYPEROS 4  ·  FREEFORM PROBE  ·  ANDROID 17");
        version.setTextColor(Palette.tertiary(this));
        version.setTextSize(11);
        version.setLetterSpacing(0.12f);
        version.setPadding(0, dp(6), 0, dp(24));
        root.addView(version);

        TextView intro = new TextView(this);
        intro.setText("0.7.0 先解决 HyperOS 4 的真实 freeform 链路：system_server 放开 MIUI freeform 门禁，SystemUI 同时尝试 MiuiTopDecoration / MiuiBaseWindowDecoration 原生顶栏。打开 App 会自动跑探针，不需要点按钮；结果页会显示 ActivityOptions 的实际参数。LSPosed 作用域：系统框架、SystemUI、HyperOS 桌面。");
        intro.setTextColor(Palette.secondary(this));
        intro.setTextSize(14);
        intro.setLineSpacing(dp(5), 1f);
        root.addView(intro, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Switch skin = new Switch(this);
        skin.setText("Flyme 皮肤（大圆角，颜色跟随系统深色）");
        skin.setTextColor(Palette.fg(this));
        skin.setTextSize(14);
        skin.setPadding(0, dp(24), 0, dp(8));
        skin.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                DemoOverlay.skin = isChecked ? DemoOverlay.SKIN_FLYME : DemoOverlay.SKIN_HYPER;
            }
        });
        root.addView(skin);

        addButton(root, "显示小窗预览", new View.OnClickListener() {
            @Override public void onClick(View v) { show("window"); }
        });
        addButton(root, "显示侧边栏", new View.OnClickListener() {
            @Override public void onClick(View v) { show("sidebar"); }
        });
        addButton(root, "显示底角扇形", new View.OnClickListener() {
            @Override public void onClick(View v) { show("radial"); }
        });

        Button stop = addButton(root, "停止预览", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startService(new Intent(MainActivity.this, OverlayService.class)
                        .setAction(OverlayService.ACTION_STOP));
            }
        });
        stop.setTextColor(Palette.danger(this));

        addButton(root, "收集并分享日志", new View.OnClickListener() {
            @Override public void onClick(View v) { LogCollector.collectAndShare(MainActivity.this); }
        });

        TextView hint = new TextView(this);
        hint.setText("提示：首次使用需授予“显示在其他应用上层”。预览会覆盖桌面，点空白处或“停止预览”退出。真实小窗的胶囊/底条只在 freeform 任务上出现。“收集并分享日志”会抓 logcat + dumpsys，没权限时自动尝试 su。");
        hint.setTextColor(Palette.tertiary(this));
        hint.setTextSize(12);
        hint.setLineSpacing(dp(4), 1f);
        hint.setPadding(0, dp(28), 0, 0);
        root.addView(hint);

        setContentView(scroll);

        // 0.6.0-selftest：打开主界面后自动进入自检页，不需要用户点任何按钮。
        // 自检页和主界面在同一个 task 里，所以它测到的就是真实 task 的 freeform 往返。
        if (savedInstanceState == null) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    startActivity(new Intent(this,
                            io.github.wumisc.nativewindow.selftest.SelfTestActivity.class));
                } catch (Throwable ignored) {}
            }, 280);
        }
    }

    private Button addButton(LinearLayout root, String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(15);
        button.setTextColor(Palette.buttonText(this));
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setBackground(round(Palette.button(this), 18, Palette.buttonStroke(this)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        lp.topMargin = dp(12);
        root.addView(button, lp);
        button.setOnClickListener(listener);
        return button;
    }

    private void show(String mode) {
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
            Toast.makeText(this, "先授予悬浮窗权限，再点一次预览", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent service = new Intent(this, OverlayService.class)
                .setAction(OverlayService.ACTION_SHOW)
                .putExtra(OverlayService.EXTRA_MODE, mode);
        startService(service);
        Toast.makeText(this, "预览已显示，点空白处退出", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY && Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "权限已授予，请选择预览", Toast.LENGTH_SHORT).show();
        }
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
}
