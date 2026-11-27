package io.github.wumisc.nativewindow;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import io.github.wumisc.nativewindow.log.LogCollector;
import io.github.wumisc.nativewindow.selftest.SelfTestActivity;

/**
 * 第一次使用引导：把“装完该干什么、现在能干什么、不能干什么”说清楚。
 * 不做假进度，状态按实际版本能力写。
 */
public class GuideActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        boolean night = Palette.isNight(this);
        getWindow().getDecorView().setSystemUiVisibility(night ? 0
                : (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Palette.bg(this));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(56), dp(24), dp(40));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView kicker = new TextView(this);
        kicker.setText("FIRST RUN  /  第一次使用");
        kicker.setTextColor(Palette.tertiary(this));
        kicker.setTextSize(11);
        kicker.setLetterSpacing(0.14f);
        root.addView(kicker);

        TextView title = new TextView(this);
        title.setText("小窗引导");
        title.setTextColor(Palette.fg(this));
        title.setTextSize(30);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setPadding(0, dp(6), 0, dp(10));
        root.addView(title);

        TextView lead = new TextView(this);
        lead.setText("先按顺序做完这 4 步，再判断它好不好用。现在这个版本是 HyperOS 4 的小窗链路验证版，不是成品。");
        lead.setTextColor(Palette.secondary(this));
        lead.setTextSize(14);
        lead.setLineSpacing(dp(5), 1f);
        root.addView(lead);

        section(root, "01  让模块真正生效");
        step(root, "1", "安装并启用模块",
                "LSPosed → 模块 → 类原生小窗 → 打开“启用模块”。");
        step(root, "2", "勾选作用域",
                "系统框架（android）、系统界面（com.android.systemui）、HyperOS 桌面（com.miui.home）。");
        step(root, "3", "重启手机",
                "作用域改完必须重启，system_server 才会重新加载模块。只重开 App 没用。");
        step(root, "4", "跑一次自检",
                "打开 App → 开始小窗自检。自检页会显示 ActivityOptions 的实际参数和任务尺寸。");

        section(root, "02  现在怎么用");
        card(root, "打开任意应用",
                "App → 选择应用开小窗 → 点任意应用。它会用 MIUI 的 freeform options 拉起，\n"
                        + "system_server 还会根据 Intent 标记再强制一次。\n"
                        + "PixelOS / LineageOS 分支可从最近任务菜单的“小窗”进入。");
        card(root, "胶囊按钮",
                "左上返回，中间标题可拖动，右侧“— / □ / ×”分别是最小化、全屏、关闭。\n"
                        + "胶囊挂在原生窗口上，移动和缩放跟随 Task surface，不是悬浮窗。");
        card(root, "拖动与缩放",
                "拖标题移动；拖窗口边缘缩放。边缘吸附和贴边最小化在 0.8 计划里，当前还没做。");

        section(root, "03  为什么现在还不如米窗");
        card(root, "差距是真实存在的",
                "米窗已经接了 HyperOS 桌面/最近任务入口、动画、贴边和迷你窗；我们还在验证\n"
                        + "“HyperOS 4 到底认哪套 freeform 启动参数”。链路没通之前，手感不可能比它好。\n"
                        + "下一步不是继续加皮肤，而是先把“从最近任务一键开小窗”打通。");

        section(root, "04  出问题怎么办");
        card(root, "先看自检结果",
                "自检页的“启动参数”应该出现 official=getActivityOptions；任务尺寸应小于屏幕。\n"
                        + "如果仍是全屏，把结果页截图发我。");
        card(root, "再分享日志",
                "App → 收集并分享日志。普通权限抓不到时它会尝试 su；root=false 也能抓 App 侧日志。");

        Button picker = button("选择应用开小窗");
        picker.setOnClickListener(v -> startActivity(new Intent(this, AppPickerActivity.class)));
        root.addView(picker, lp());

        Button selftest = button("开始小窗自检");
        selftest.setOnClickListener(v -> startActivity(new Intent(this, SelfTestActivity.class)));
        root.addView(selftest, lp());

        Button log = button("收集并分享日志");
        log.setTextColor(Palette.secondary(this));
        log.setBackgroundColor(Color.TRANSPARENT);
        log.setOnClickListener(v -> LogCollector.collectAndShare(this));
        root.addView(log, lp());

        Button back = button("返回主界面");
        back.setTextColor(Palette.secondary(this));
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setOnClickListener(v -> finish());
        root.addView(back, lp());

        setContentView(scroll);
    }

    private void section(LinearLayout root, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Palette.fg(this));
        t.setTextSize(16);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(26), 0, dp(10));
        root.addView(t);
    }

    private void step(LinearLayout root, String num, String title, String body) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, dp(8));

        TextView n = new TextView(this);
        n.setText(num);
        n.setTextColor(Palette.buttonText(this));
        n.setTextSize(13);
        n.setGravity(Gravity.CENTER);
        n.setBackground(round(Palette.button(this), 18, 0));
        row.addView(n, new LinearLayout.LayoutParams(dp(30), dp(30)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, 0, 0);
        TextView h = new TextView(this);
        h.setText(title);
        h.setTextColor(Palette.fg(this));
        h.setTextSize(15);
        h.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        col.addView(h);
        TextView b = new TextView(this);
        b.setText(body);
        b.setTextColor(Palette.secondary(this));
        b.setTextSize(13);
        b.setLineSpacing(dp(4), 1f);
        b.setPadding(0, dp(4), 0, 0);
        col.addView(b);
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row);
    }

    private void card(LinearLayout root, String title, String body) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        box.setBackground(round(Palette.card(this), 18, Palette.cardStroke(this)));
        TextView h = new TextView(this);
        h.setText(title);
        h.setTextColor(Palette.fg(this));
        h.setTextSize(15);
        h.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        box.addView(h);
        TextView b = new TextView(this);
        b.setText(body);
        b.setTextColor(Palette.secondary(this));
        b.setTextSize(13);
        b.setLineSpacing(dp(5), 1f);
        b.setPadding(0, dp(6), 0, 0);
        box.addView(b);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(10);
        root.addView(box, p);
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(Palette.buttonText(this));
        b.setBackground(round(Palette.button(this), 18, Palette.buttonStroke(this)));
        return b;
    }

    private LinearLayout.LayoutParams lp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        p.topMargin = dp(12);
        return p;
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
