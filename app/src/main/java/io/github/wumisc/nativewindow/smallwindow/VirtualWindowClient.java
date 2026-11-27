package io.github.wumisc.nativewindow.smallwindow;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/**
 * 应用侧入口：把小窗请求交给 system_server 的 VirtualWindowEngine。
 *
 * 这条链路和 freeform/WCT 无关：system_server 会创建一块独立虚拟屏，
 * 把目标 Activity 启动到这块屏上，再把画面镜像到一个可拖动/缩放的悬浮卡片里。
 * 这样不依赖 ROM 是否暴露 freeform caption，也不会出现“任务全屏、胶囊没地方挂”。
 */
public final class VirtualWindowClient {
    public static final String ACTION_OPEN =
            "io.github.wumisc.nativewindow.action.OPEN_VIRTUAL_WINDOW";
    public static final String ACTION_CLOSE_ALL =
            "io.github.wumisc.nativewindow.action.CLOSE_VIRTUAL_WINDOWS";
    public static final String EXTRA_PACKAGE = "pkg";
    public static final String EXTRA_COMPONENT = "component";
    public static final String EXTRA_TASK_ID = "task_id";
    public static final String EXTRA_USER_ID = "user_id";
    public static final String EXTRA_SOURCE = "source";

    private VirtualWindowClient() {}

    public static boolean open(Context context, String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            Intent launch = pm.getLaunchIntentForPackage(packageName);
            if (launch == null || launch.getComponent() == null) return false;
            ComponentName cn = launch.getComponent();
            Intent intent = new Intent(ACTION_OPEN);
            intent.setPackage("android");
            intent.putExtra(EXTRA_PACKAGE, packageName);
            intent.putExtra(EXTRA_COMPONENT, cn);
            intent.putExtra(EXTRA_USER_ID, 0);
            intent.putExtra(EXTRA_SOURCE, "picker");
            context.sendBroadcast(intent);
            return true;
        } catch (Throwable t) {
            android.util.Log.e("NativeWindow/VW", "open " + packageName + " failed", t);
            return false;
        }
    }

    public static boolean openTask(Context context, int taskId, ComponentName component, int userId) {
        try {
            Intent intent = new Intent(ACTION_OPEN);
            intent.setPackage("android");
            intent.putExtra(EXTRA_TASK_ID, taskId);
            if (component != null) intent.putExtra(EXTRA_COMPONENT, component);
            intent.putExtra(EXTRA_USER_ID, userId);
            intent.putExtra(EXTRA_SOURCE, "recents");
            context.sendBroadcast(intent);
            return true;
        } catch (Throwable t) {
            android.util.Log.e("NativeWindow/VW", "openTask " + taskId + " failed", t);
            return false;
        }
    }
}
