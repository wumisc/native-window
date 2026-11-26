package io.github.wumisc.nativewindow;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
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

public class MainActivity extends Activity {
    private static final int REQ_OVERLAY = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF08090C);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(56), dp(24), dp(40));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("类原生小窗");
        title.setTextColor(0xFFF5F5F7);
        title.setTextSize(30);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView version = new TextView(this);
        version.setText("HYPEROS 3 / FLYME  ·  VISUAL ALPHA");
        version.setTextColor(0xFF8E8E93);
        version.setTextSize(11);
        version.setLetterSpacing(0.12f);
        version.setPadding(0, dp(6), 0, dp(24));
        root.addView(version);

        TextView intro = new TextView(this);
        intro.setText("先把国产 UI 的观感做对：圆角、胶囊、底部把手、阴影、边缘吸附、侧边栏与底角扇形。本版是可安装的视觉基线，系统级开窗内核按同一套 chrome 接入。");
        intro.setTextColor(0xFFB8B8BE);
        intro.setTextSize(14);
        intro.setLineSpacing(dp(5), 1f);
        root.addView(intro, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Switch skin = new Switch(this);
        skin.setText("Flyme 皮肤（浅色 / 大圆角）");
        skin.setTextColor(0xFFE5E5EA);
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
        stop.setTextColor(0xFFFF6B6B);

        TextView hint = new TextView(this);
        hint.setText("提示：首次使用需授予“显示在其他应用上层”。预览会覆盖桌面，点空白处或“停止预览”退出。");
        hint.setTextColor(0xFF7C7C82);
        hint.setTextSize(12);
        hint.setLineSpacing(dp(4), 1f);
        hint.setPadding(0, dp(28), 0, 0);
        root.addView(hint);

        setContentView(scroll);
    }

    private Button addButton(LinearLayout root, String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(15);
        button.setTextColor(0xFFF2F2F7);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setBackground(round(0xFF1C1C1E, 18, 0x33FFFFFF));
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
