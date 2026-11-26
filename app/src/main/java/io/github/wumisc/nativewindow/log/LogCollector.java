package io.github.wumisc.nativewindow.log;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import io.github.wumisc.nativewindow.Palette;

/**
 * 收集模块自身 + 系统窗口相关日志。
 *
 * 普通 App 没有 READ_LOGS，所以先走普通 logcat；如果输出为空/权限拒绝，再尝试 su。
 * dumpsys 在多数 ROM 上也需要 root；这里尽量拿，拿不到就写失败原因，不阻塞分享。
 */
public final class LogCollector {
    private static final int MAX_CHARS = 140000;
    private static final String[] TAGS = {
            "NativeWindow:V", "NativeWindow/SS:V", "NativeWindow/SUI:V",
            "NativeWindow/Launcher:V", "LSPosed:V", "Xposed:V",
            "AndroidRuntime:E", "ActivityManager:E", "WindowManager:E"
    };

    private LogCollector() {}

    public static void collectAndShare(Activity activity) {
        Toast.makeText(activity, "正在收集日志…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            File file = null;
            try {
                file = collect(activity);
            } catch (Throwable ignored) {
            }
            final File out = file;
            activity.runOnUiThread(() -> {
                if (out == null) {
                    Toast.makeText(activity, "日志收集失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                share(activity, out);
            });
        }, "native-window-log").start();
    }

    public static File collect(Context context) throws Exception {
        File dir = new File(context.getCacheDir(), "logs");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("mkdir failed");
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File out = new File(dir, "native-window-" + stamp + ".txt");
        StringBuilder sb = new StringBuilder(200000);

        sb.append("native-window log\n");
        sb.append("time=").append(new Date()).append('\n');
        sb.append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("release=").append(Build.VERSION.RELEASE)
                .append(" sdk=").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("build=").append(Build.DISPLAY).append('\n');
        sb.append("night=").append(Palette.isNight(context)).append('\n');
        sb.append("overlay=").append(Settings.canDrawOverlays(context)).append('\n');
        try {
            PackageInfo pi = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            sb.append("module=").append(pi.versionName)
                    .append(" (").append(pi.versionCode).append(")\n");
        } catch (Throwable ignored) {}
        sb.append("note=普通 logcat 没权限时自动尝试 su；dumpsys 也尽量走 su。\n\n");

        String normalLog = run("logcat -d -v threadtime -t 1600 " + tags(), false);
        if (!useful(normalLog)) {
            sb.append("=== logcat: normal failed, trying su ===\n");
            normalLog = run("logcat -d -v threadtime -t 2000 " + tags(), true);
        }
        section(sb, "logcat", normalLog);

        boolean root = isRoot();
        sb.append("root=").append(root).append("\n\n");
        section(sb, "dumpsys activity activities",
                run("dumpsys activity activities", root));
        section(sb, "dumpsys window windows",
                run("dumpsys window windows", root));
        section(sb, "dumpsys activity processes",
                run("dumpsys activity processes", root));

        try (FileWriter w = new FileWriter(out)) {
            w.write(sb.toString());
        }
        return out;
    }

    private static String tags() {
        StringBuilder b = new StringBuilder();
        for (String t : TAGS) b.append(' ').append(t);
        return b.toString();
    }

    private static boolean useful(String s) {
        if (s == null) return false;
        String low = s.toLowerCase(Locale.US);
        if (low.contains("permission denied") || low.contains("not found")) return false;
        return s.trim().length() > 120;
    }

    private static boolean isRoot() {
        String id = run("id", true);
        return id != null && id.contains("uid=0");
    }

    private static String run(String command, boolean root) {
        Process process = null;
        Thread reader = null;
        try {
            ProcessBuilder pb = root
                    ? new ProcessBuilder("su", "-c", command)
                    : new ProcessBuilder("sh", "-c", command);
            pb.redirectErrorStream(true);
            process = pb.start();
            final StringBuffer out = new StringBuffer();
            final Process p = process;
            reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(
                        p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (out.length() >= MAX_CHARS) {
                            out.append("\n[truncated]\n");
                            p.destroy();
                            break;
                        }
                        out.append(line).append('\n');
                    }
                } catch (Throwable ignored) {}
            }, "log-reader");
            reader.start();
            if (!process.waitFor(12, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                out.append("\n[timeout]\n");
            }
            reader.join(2500);
            return out.toString();
        } catch (Throwable t) {
            return "[run failed] " + command + " :: " + t + "\n";
        } finally {
            if (process != null) process.destroy();
            if (reader != null) reader.interrupt();
        }
    }

    private static void section(StringBuilder sb, String title, String body) {
        sb.append("=== ").append(title).append(" ===\n");
        if (body == null || body.isEmpty()) {
            sb.append("[empty]\n\n");
            return;
        }
        sb.append(body);
        if (!body.endsWith("\n")) sb.append('\n');
        sb.append('\n');
    }

    private static void share(Activity activity, File file) {
        try {
            Uri uri = LogProvider.uriFor(file);
            Intent send = new Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, "native-window log")
                    .putExtra(Intent.EXTRA_TEXT, "native-window log: " + file.getName())
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(ClipData.newRawUri("native-window-log", uri));
            activity.startActivity(Intent.createChooser(send, "分享 native-window 日志"));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, "没有可分享的应用", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(activity, "分享失败：" + t, Toast.LENGTH_LONG).show();
        }
    }

    private static final class IOException extends Exception {
        IOException(String message) { super(message); }
    }
}
