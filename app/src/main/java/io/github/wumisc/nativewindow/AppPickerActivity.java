package io.github.wumisc.nativewindow;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.wumisc.nativewindow.smallwindow.FreeformLauncher;

/** 应用选择器：点任意应用，以 freeform 小窗打开。 */
public class AppPickerActivity extends Activity {
    private LinearLayout list;
    private TextView empty;
    private final List<AppEntry> apps = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean night = Palette.isNight(this);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(night ? 0
                : (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Palette.bg(this));
        root.setPadding(dp(20), dp(48), dp(20), dp(20));

        TextView title = new TextView(this);
        title.setText("选择应用开小窗");
        title.setTextColor(Palette.fg(this));
        title.setTextSize(24);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("点击任意应用，直接以自由窗口打开。第一次没反应就再点一次。");
        sub.setTextColor(Palette.secondary(this));
        sub.setTextSize(13);
        sub.setPadding(0, dp(6), 0, dp(14));
        root.addView(sub);

        EditText search = new EditText(this);
        search.setHint("搜索应用");
        search.setTextSize(14);
        search.setSingleLine(true);
        search.setTextColor(Palette.fg(this));
        search.setHintTextColor(Palette.tertiary(this));
        search.setBackground(round(Palette.card(this), 18, Palette.cardStroke(this)));
        search.setPadding(dp(16), 0, dp(16), 0);
        root.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(8), 0, dp(24));
        scroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        empty = new TextView(this);
        empty.setText("没有匹配的应用");
        empty.setTextColor(Palette.tertiary(this));
        empty.setTextSize(13);
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(View.GONE);
        root.addView(empty);

        setContentView(root);
        loadApps();
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                render(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        render("");
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(main, 0);
        for (ResolveInfo info : infos) {
            if (info.activityInfo == null) continue;
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            if (pkg.equals("com.android.systemui")) continue;
            CharSequence label = info.loadLabel(pm);
            AppEntry e = new AppEntry(pkg, label == null ? pkg : label.toString(), info);
            apps.add(e);
        }
        Collator c = Collator.getInstance();
        Collections.sort(apps, (a, b) -> c.compare(a.label, b.label));
    }

    private void render(String query) {
        list.removeAllViews();
        String q = query == null ? "" : query.trim().toLowerCase();
        int shown = 0;
        for (AppEntry e : apps) {
            if (!q.isEmpty() && !e.label.toLowerCase().contains(q)
                    && !e.pkg.toLowerCase().contains(q)) continue;
            list.addView(row(e));
            shown++;
        }
        empty.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
    }

    private View row(AppEntry e) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(round(Palette.card(this), 16, Palette.cardStroke(this)));

        ImageView icon = new ImageView(this);
        try {
            Drawable d = getPackageManager().getApplicationIcon(e.pkg);
            icon.setImageDrawable(d);
        } catch (Throwable t) {
            icon.setImageResource(android.R.drawable.sym_def_app_icon);
        }
        row.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, 0, 0);
        TextView name = new TextView(this);
        name.setText(e.label);
        name.setTextColor(Palette.fg(this));
        name.setTextSize(15);
        col.addView(name);
        TextView pkg = new TextView(this);
        pkg.setText(e.pkg);
        pkg.setTextColor(Palette.tertiary(this));
        pkg.setTextSize(10);
        pkg.setPadding(0, dp(2), 0, 0);
        col.addView(pkg);
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView action = new TextView(this);
        action.setText("小窗");
        action.setTextColor(Palette.secondary(this));
        action.setTextSize(12);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(10), dp(6), dp(10), dp(6));
        action.setBackground(round(Palette.iconPlate(this), 12, 0));
        row.addView(action);

        row.setOnClickListener(v -> {
            boolean ok = FreeformLauncher.open(this, e.pkg);
            Toast.makeText(this, ok ? "正在打开 " + e.label : "打开失败：" + e.pkg,
                    Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(8);
        row.setLayoutParams(p);
        return row;
    }

    private static final class AppEntry {
        final String pkg;
        final String label;
        AppEntry(String pkg, String label, ResolveInfo info) {
            this.pkg = pkg;
            this.label = label;
        }
    }

    private android.graphics.drawable.GradientDrawable round(int color, int radiusDp, int stroke) {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
