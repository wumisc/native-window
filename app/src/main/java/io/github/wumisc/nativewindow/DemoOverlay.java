package io.github.wumisc.nativewindow;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import io.github.wumisc.nativewindow.anim.SpringAnimator;

import java.util.ArrayList;
import java.util.List;

import io.github.wumisc.nativewindow.anim.SpringAnimator;

public class DemoOverlay {
    public static final int SKIN_HYPER = 0;
    public static final int SKIN_FLYME = 1;
    public static int skin = SKIN_HYPER;

    private final Context context;
    private final WindowManager wm;
    private FrameLayout root;
    private FrameLayout card;
    private ImageView miniChip;
    private LinearLayout sidebar;
    private View sidebarHandle;
    private RadialView radial;
    private int screenW, screenH;
    private int cardW, cardH;
    private boolean isMini;
    private final List<AppEntry> apps = new ArrayList<>();

    public DemoOverlay(Context context) {
        this.context = context;
        this.wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        collectApps();
    }

    /** 系统深色优先；系统浅色时 Hyper 皮肤仍用深色，Flyme 皮肤用浅色。 */
    private boolean dark() { return skin == SKIN_HYPER || Palette.isNight(context); }
    private int radius() { return skin == SKIN_FLYME ? 28 : 22; }
    private int fg() { return Palette.fg(context); }
    private int sub() { return Palette.secondary(context); }
    private int cardColor() { return Palette.card(context); }
    private int capsuleColor() { return Palette.capsule(context); }

    public void show(String mode) {
        hide();
        if (wm == null) return;
        screenW = wm.getCurrentWindowMetrics().getBounds().width();
        screenH = wm.getCurrentWindowMetrics().getBounds().height();
        cardW = Math.min((int) (screenW * 0.86f), dp(520));
        cardH = (int) (screenH * 0.58f);

        root = new FrameLayout(context);
        root.setClipChildren(false);
        root.setClipToPadding(false);

        View dim = new View(context);
        dim.setBackgroundColor(Palette.dim(context));
        dim.setOnClickListener(v -> hide());
        root.addView(dim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        buildCard();
        buildSidebar();
        buildMiniChip();

        radial = new RadialView(context, apps, entry -> {
            Toast.makeText(context, "打开 " + entry.label, Toast.LENGTH_SHORT).show();
            radial.setVisibility(View.GONE);
        });
        radial.setVisibility(View.GONE);
        root.addView(radial, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        wm.addView(root, lp);

        card.setAlpha(0f);
        card.setScaleX(0.92f);
        card.setScaleY(0.92f);
        SpringAnimator.windowOpen(v -> { card.setScaleX(v); card.setScaleY(v); })
                .start(0.92f, 1f);
        SpringAnimator.windowOpen(v -> card.setAlpha(v)).start(0f, 1f);

        if ("sidebar".equals(mode)) openSidebar(true);
        if ("radial".equals(mode)) showRadial();
    }

    private void buildCard() {
        card = new FrameLayout(context);
        card.setBackground(round(cardColor(), radius(), Palette.cardStroke(context)));
        card.setElevation(dp(28));
        card.setClipToOutline(true);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(cardW, cardH);
        cardLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        cardLp.topMargin = (int) (screenH * 0.15f);
        root.addView(card, cardLp);

        // 顶部胶囊：三个点，可拖动
        FrameLayout top = new FrameLayout(context);
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        card.addView(top, topLp);

        LinearLayout capsule = new LinearLayout(context);
        capsule.setOrientation(LinearLayout.HORIZONTAL);
        capsule.setGravity(Gravity.CENTER);
        capsule.setPadding(dp(12), 0, dp(12), 0);
        capsule.setBackground(round(capsuleColor(), 18, Palette.cardStroke(context)));
        FrameLayout.LayoutParams capLp = new FrameLayout.LayoutParams(dp(132), dp(34));
        capLp.gravity = Gravity.CENTER;
        top.addView(capsule, capLp);

        View close = dot(0xFFFF5F57);
        View menu = dot(0xFF8E8E93);
        View split = dot(0xFF8E8E93);
        capsule.addView(close, dotLp());
        capsule.addView(menu, dotLp());
        capsule.addView(split, dotLp());
        close.setOnClickListener(v -> hide());
        menu.setOnClickListener(v -> Toast.makeText(context, "更多菜单", Toast.LENGTH_SHORT).show());
        split.setOnClickListener(v -> Toast.makeText(context, "分屏暂缓", Toast.LENGTH_SHORT).show());

        top.setOnTouchListener(new DragListener());

        // 内容
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(58), dp(22), dp(26));
        FrameLayout.LayoutParams contentLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        card.addView(content, contentLp);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(62)));

        ImageView icon = new ImageView(context);
        icon.setImageDrawable(apps.get(0).icon);
        icon.setBackground(round(Palette.iconPlate(context), 14, 0));
        icon.setPadding(dp(8), dp(8), dp(8), dp(8));
        header.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(14), 0, 0, 0);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView title = text(apps.get(0).label, 22, fg(), true);
        titles.addView(title);
        TextView subtitle = text("小窗 · 自由拖动 / 缩放 / 贴边", 12, sub(), false);
        subtitle.setPadding(0, dp(3), 0, 0);
        titles.addView(subtitle);

        View more = text("⋯", 22, sub(), true);
        more.setPadding(dp(8), 0, dp(8), 0);
        header.addView(more);

        content.addView(divider());

        for (int i = 1; i < Math.min(apps.size(), 6); i++) {
            addRow(content, apps.get(i));
        }

        // 底部把手
        View handle = new View(context);
        handle.setBackground(round(Palette.handle(context), 3, 0));
        FrameLayout.LayoutParams handleLp = new FrameLayout.LayoutParams(dp(96), dp(5));
        handleLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = dp(10);
        card.addView(handle, handleLp);
        handle.setOnClickListener(v -> mini());
    }

    private void addRow(LinearLayout parent, AppEntry entry) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        ImageView icon = new ImageView(context);
        icon.setImageDrawable(entry.icon);
        icon.setBackground(round(Palette.sidebarIcon(context), 12, 0));
        icon.setPadding(dp(8), dp(8), dp(8), dp(8));
        row.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(38)));

        TextView label = text(entry.label, 15, fg(), false);
        label.setPadding(dp(14), 0, 0, 0);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView arrow = text("›", 22, sub(), false);
        row.addView(arrow);

        row.setOnClickListener(v -> Toast.makeText(context, entry.label + " · 预览项", Toast.LENGTH_SHORT).show());
    }

    private void buildSidebar() {
        sidebar = new LinearLayout(context);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setPadding(dp(10), dp(74), dp(10), dp(30));
        sidebar.setBackground(round(Palette.sidebar(context), 20, Palette.sidebarStroke(context)));
        sidebar.setElevation(dp(18));
        sidebar.setVisibility(View.GONE);

        FrameLayout.LayoutParams sideLp = new FrameLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.MATCH_PARENT);
        sideLp.gravity = Gravity.START;
        sideLp.leftMargin = dp(10);
        sideLp.topMargin = dp(34);
        sideLp.bottomMargin = dp(24);
        root.addView(sidebar, sideLp);

        for (int i = 0; i < Math.min(apps.size(), 8); i++) {
            AppEntry entry = apps.get(i);
            ImageView icon = new ImageView(context);
            icon.setImageDrawable(entry.icon);
            icon.setPadding(dp(9), dp(9), dp(9), dp(9));
            icon.setBackground(round(Palette.sidebarIcon(context), 13, 0));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(48));
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            lp.topMargin = dp(9);
            sidebar.addView(icon, lp);
            icon.setOnClickListener(v -> Toast.makeText(context, entry.label, Toast.LENGTH_SHORT).show());
        }

        sidebarHandle = new View(context);
        sidebarHandle.setBackground(round(Palette.sidebarHandle(context), 4, 0));
        FrameLayout.LayoutParams handleLp = new FrameLayout.LayoutParams(dp(5), dp(74));
        handleLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        handleLp.leftMargin = dp(2);
        root.addView(sidebarHandle, handleLp);
        sidebarHandle.setOnClickListener(v -> openSidebar(sidebar.getVisibility() != View.VISIBLE));
        sidebarHandle.setOnTouchListener(new SidebarDrag());
    }

    private void buildMiniChip() {
        miniChip = new ImageView(context);
        miniChip.setImageDrawable(apps.get(0).icon);
        miniChip.setPadding(dp(9), dp(9), dp(9), dp(9));
        miniChip.setBackground(round(cardColor(), 18, Palette.cardStroke(context)));
        miniChip.setElevation(dp(18));
        miniChip.setVisibility(View.GONE);
        FrameLayout.LayoutParams chipLp = new FrameLayout.LayoutParams(dp(56), dp(56));
        chipLp.gravity = Gravity.BOTTOM | Gravity.END;
        chipLp.rightMargin = dp(18);
        chipLp.bottomMargin = dp(96);
        root.addView(miniChip, chipLp);
        miniChip.setOnClickListener(v -> restore());
    }

    private void mini() {
        if (card.getVisibility() != View.VISIBLE) return;
        isMini = true;
        SpringAnimator.snap(v -> {
            float t = Math.max(0f, Math.min(1f, v));
            card.setScaleX(0.2f + 0.8f * t);
            card.setScaleY(0.2f + 0.8f * t);
            card.setAlpha(t);
        }).start(1f, 0f);
        miniChip.postDelayed(() -> {
            card.setVisibility(View.GONE);
            miniChip.setVisibility(View.VISIBLE);
            miniChip.setAlpha(0f);
            miniChip.setScaleX(0.6f);
            miniChip.setScaleY(0.6f);
            SpringAnimator.snap(v -> {
                miniChip.setScaleX(v);
                miniChip.setScaleY(v);
                miniChip.setAlpha(v);
            }).start(0.6f, 1f);
        }, 300);
    }

    private void restore() {
        isMini = false;
        miniChip.setVisibility(View.GONE);
        card.setVisibility(View.VISIBLE);
        card.setAlpha(0f);
        card.setScaleX(0.2f);
        card.setScaleY(0.2f);
        SpringAnimator.windowOpen(v -> {
            float t = Math.max(0f, Math.min(1f, v));
            card.setScaleX(0.2f + 0.8f * t);
            card.setScaleY(0.2f + 0.8f * t);
            card.setAlpha(t);
        }).start(0f, 1f);
    }

    private void showRadial() {
        if (radial == null) return;
        radial.setVisibility(View.VISIBLE);
        radial.reveal();
    }

    private void openSidebar(boolean open) {
        float w = dp(86);
        if (open) {
            sidebar.setVisibility(View.VISIBLE);
            sidebar.setTranslationX(-w);
            SpringAnimator.windowOpen(v -> sidebar.setTranslationX(v)).start(-w, 0f);
        } else {
            SpringAnimator.snap(v -> sidebar.setTranslationX(v)).start(0f, -w);
            sidebar.postDelayed(() -> sidebar.setVisibility(View.GONE), 260);
        }
    }

    private void snapIfNeeded() {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
        int left = lp.leftMargin;
        int right = lp.leftMargin + cardW;
        if (left < dp(18) || right > screenW - dp(18)) {
            mini();
        }
    }

    private class DragListener implements View.OnTouchListener {
        float downX, downY;
        int startLeft, startTop;
        boolean moved;

        @Override public boolean onTouch(View v, MotionEvent event) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    // 初始是 CENTER_HORIZONTAL，leftMargin 为 0；先固化到当前实际位置，避免起拖跳变
                    startLeft = card.getLeft();
                    startTop = card.getTop();
                    lp.leftMargin = startLeft;
                    lp.topMargin = startTop;
                    lp.gravity = Gravity.TOP | Gravity.START;
                    card.setLayoutParams(lp);
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (event.getRawX() - downX);
                    int dy = (int) (event.getRawY() - downY);
                    if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) moved = true;
                    lp.leftMargin = startLeft + dx;
                    lp.topMargin = Math.max(dp(24), startTop + dy);
                    lp.gravity = Gravity.TOP | Gravity.START;
                    card.setLayoutParams(lp);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (moved) snapIfNeeded();
                    return true;
            }
            return false;
        }
    }

    private class SidebarDrag implements View.OnTouchListener {
        float downX;
        @Override public boolean onTouch(View v, MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) { downX = event.getRawX(); return true; }
            if (event.getAction() == MotionEvent.ACTION_MOVE && event.getRawX() - downX > dp(12)) {
                openSidebar(true);
                return true;
            }
            return false;
        }
    }

    public void hide() {
        if (root != null && wm != null) {
            try { wm.removeView(root); } catch (Throwable ignored) {}
        }
        root = null;
        card = null;
        sidebar = null;
        radial = null;
        miniChip = null;
    }

    private View dot(int color) {
        View v = new View(context);
        v.setBackground(round(color, 4, 0));
        return v;
    }

    private LinearLayout.LayoutParams dotLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(7), dp(7));
        lp.leftMargin = dp(7);
        lp.rightMargin = dp(7);
        return lp;
    }

    private View divider() {
        View v = new View(context);
        v.setBackgroundColor(dark() ? 0x14FFFFFF : Palette.sidebarIcon(context));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(2);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(context);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable round(int color, int radiusDp, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private int dp(float v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }

    private void collectApps() {
        String[][] defs = {
                {"设置", "com.android.settings"},
                {"相机", "com.android.camera2"},
                {"浏览器", "com.android.chrome"},
                {"电话", "com.google.android.dialer"},
                {"信息", "com.android.mms"},
                {"日历", "com.android.calendar"},
                {"文件", "com.android.documentsui"},
                {"时钟", "com.android.deskclock"}
        };
        PackageManager pm = context.getPackageManager();
        for (String[] def : defs) {
            Drawable icon;
            try {
                icon = pm.getApplicationIcon(def[1]);
            } catch (Throwable t) {
                icon = context.getDrawable(android.R.drawable.ic_menu_manage);
            }
            apps.add(new AppEntry(def[0], def[1], icon));
        }
    }

    public static class AppEntry {
        public final String label;
        public final String pkg;
        public final Drawable icon;
        public AppEntry(String label, String pkg, Drawable icon) {
            this.label = label; this.pkg = pkg; this.icon = icon;
        }
    }

    /** 底角扇形：独立 View，负责画 7 个图标与选中。 */
    public static class RadialView extends View {
        interface Listener { void onSelected(AppEntry entry); }
        private final List<AppEntry> entries;
        private final Listener listener;
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int selected = -1;
        private float cx, cy, radius;
        private float reveal = 1f;
        private final boolean dark;

        public RadialView(Context context, List<AppEntry> entries, Listener listener) {
            super(context);
            this.entries = entries;
            this.listener = listener;
            this.dark = skin == SKIN_HYPER || Palette.isNight(context);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            textPaint.setColor(Palette.fg(context));
            textPaint.setTextSize(dp(11));
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            cx = dp(18);
            cy = h - dp(18);
            radius = Math.min(w, h) * 0.34f;
        }

        public void reveal() {
            reveal = 0f;
            SpringAnimator.radial(v -> { reveal = Math.max(0f, Math.min(1f, v)); invalidate(); })
                    .start(0f, 1f);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int alpha = (int) (255 * reveal);
            float scale = 0.78f + 0.22f * reveal;
            bg.setShader(new RadialGradient(cx, cy, (radius + dp(30)) * scale,
                    dark ? 0x33111111 : 0x22000000,
                    dark ? 0x00111111 : 0x00111111, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, (radius + dp(30)) * scale, bg);
            bg.setShader(null);

            for (int i = 0; i < Math.min(entries.size(), 7); i++) {
                float angle = (float) Math.toRadians(8 + i * 12);
                float rr = radius * scale;
                float x = cx + (float) (rr * Math.cos(angle));
                float y = cy - (float) (rr * Math.sin(angle));
                float r = dp(i == selected ? 31 : 28) * scale;
                iconPaint.setAlpha(alpha);
                iconPaint.setColor(i == selected
                        ? (dark ? 0xCC1C1C1E : 0xFFFFFFFF)
                        : (dark ? 0x991C1C1E : 0xE6FFFFFF));
                iconPaint.setShadowLayer(dp(8), 0, dp(3), dark ? 0x66000000 : 0x33000000);
                canvas.drawCircle(x, y, r, iconPaint);
                Drawable d = entries.get(i).icon;
                d.setAlpha(alpha);
                d.setBounds((int) (x - r + dp(7)), (int) (y - r + dp(7)),
                        (int) (x + r - dp(7)), (int) (y + r - dp(7)));
                d.draw(canvas);
                textPaint.setAlpha(alpha);
                canvas.drawText(entries.get(i).label, x, y + r + dp(13), textPaint);
            }
            textPaint.setColor(dark ? 0x99FFFFFF : 0x99000000);
            canvas.drawText("斜向内上滑 · 松手打开", cx + dp(120), cy - dp(8), textPaint);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) { selected = nearest(e.getX(), e.getY()); invalidate(); return true; }
            if (e.getAction() == MotionEvent.ACTION_MOVE) { selected = nearest(e.getX(), e.getY()); invalidate(); return true; }
            if (e.getAction() == MotionEvent.ACTION_UP) {
                if (selected >= 0 && selected < entries.size()) listener.onSelected(entries.get(selected));
                return true;
            }
            return true;
        }

        private int nearest(float x, float y) {
            int best = -1;
            float bestD = Float.MAX_VALUE;
            for (int i = 0; i < Math.min(entries.size(), 7); i++) {
                float angle = (float) Math.toRadians(8 + i * 12);
                float ix = cx + (float) (radius * Math.cos(angle));
                float iy = cy - (float) (radius * Math.sin(angle));
                float d = (x - ix) * (x - ix) + (y - iy) * (y - iy);
                if (d < bestD) { bestD = d; best = i; }
            }
            return bestD < dp(54) * dp(54) ? best : -1;
        }

        private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    }
}
