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
        version.setText("1.0.0 ALPHA  ·  VIRTUAL DISPLAY  ·  AOSP 17");
        version.setTextColor(Palette.tertiary(this));
        version.setTextSize(11);
        version.setLetterSpacing(0.12f);
        version.setPadding(0, dp(6), 0, dp(24));
        root.addView(version);

        TextView intro = new TextView(this);
        intro.setText("真实小窗路线：system_server 创建独立虚拟屏，把目标应用启动到这块屏，再用 TextureView 镜像到可拖动、可缩放、可全屏的悬浮卡片里。不依赖 ROM 的 freeform 标题栏，也不再把“预览壳”当成小窗。");
        intro.setTextColor(Palette.secondary(this));
        intro.setTextSize(14);
        intro.setLineSpacing(dp(5), 1f);
        root.addView(intro, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addButton(root, "选择应用开小窗", new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(
                    MainActivity.this, AppPickerActivity.class)); }
        });

        addButton(root, "使用引导 / 第一次使用", new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(
                    MainActivity.this, GuideActivity.class)); }
        });

        addButton(root, "开始小窗自检", new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(
                    MainActivity.this, io.github.wumisc.nativewindow.selftest.SelfTestActivity.class)); }
        });

        addButton(root, "关闭所有小窗", new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent close = new Intent(
                        io.github.wumisc.nativewindow.smallwindow.VirtualWindowClient.ACTION_CLOSE_ALL);
                close.setPackage("android");
                sendBroadcast(close);
                Toast.makeText(MainActivity.this, "已发送关闭请求", Toast.LENGTH_SHORT).show();
            }
        });

        addButton(root, "收集并分享日志", new View.OnClickListener() {
            @Override public void onClick(View v) { LogCollector.collectAndShare(MainActivity.this); }
        });

        TextView hint = new TextView(this);
        hint.setText("提示：LSPosed 作用域需要勾选“系统框架”，并重启系统框架。小窗画面是真实应用，输入会按虚拟屏 displayId 注入。若卡片没出现，先看 LSPosed 日志里的 NativeWindow/VWE。");
        hint.setTextColor(Palette.tertiary(this));
        hint.setTextSize(12);
        hint.setLineSpacing(dp(4), 1f);
        hint.setPadding(0, dp(28), 0, 0);
        root.addView(hint);

        setContentView(scroll);

        // 0.7.2：不再自动跳自检页。第一次打开先看“使用引导”，避免用户
        // 一进来就被探针页挡住，不知道模块到底该怎么配。
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
