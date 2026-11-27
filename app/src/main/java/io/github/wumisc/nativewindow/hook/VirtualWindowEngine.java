package io.github.wumisc.nativewindow.hook;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.wumisc.nativewindow.smallwindow.VirtualWindowClient;

/**
 * system_server 里的虚拟屏小窗引擎（米窗路线）。
 *
 * 关键点不是把真实 task 改成 freeform，而是：
 * 1. 用 DisplayManager 创建一块 TRUSTED 虚拟屏；
 * 2. 把 Activity 启动/移动到这块屏；
 * 3. 用 TextureView 把这块屏的画面镜像到 TYPE_APPLICATION_OVERLAY 卡片里；
 * 4. 卡片上的触摸按虚拟屏 displayId 注入，应用收到的是真实输入。
 *
 * 这样不需要 ROM 提供 freeform caption，也不会出现“窗口是全屏、没有标题栏”的假小窗。
 */
public final class VirtualWindowEngine {
    private static final String TAG = "NativeWindow/VWE";
    private static final String MODULE_PKG = "io.github.wumisc.nativewindow";

    private static Context sContext;
    private static Context sWindowContext;
    private static WindowManager sWindowManager;
    private static DisplayManager sDisplayManager;
    private static Object sActivityTaskManager;
    private static int sModuleUid = -1;
    private static boolean sRegistered;

    private static final List<VirtualWindow> sWindows = new ArrayList<>();

    private VirtualWindowEngine() {}

    public static void init(Object activityManagerService) {
        try {
            if (sRegistered) return;
            Object raw = XposedHelpers.getObjectField(activityManagerService, "mContext");
            if (!(raw instanceof Context)) {
                Log.w(TAG, "AMS.mContext is not Context: " + raw);
                return;
            }
            sContext = (Context) raw;
            sDisplayManager = (DisplayManager) sContext.getSystemService(Context.DISPLAY_SERVICE);
            Display display = sDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
            sWindowContext = sContext.createDisplayContext(display);
            sWindowManager = (WindowManager) sWindowContext.getSystemService(Context.WINDOW_SERVICE);
            sActivityTaskManager = atm();
            try {
                sModuleUid = sContext.getPackageManager().getPackageUid(MODULE_PKG, 0);
            } catch (Throwable t) {
                sModuleUid = -1;
                XposedBridge.log(TAG + ": module uid unresolved, accept broadcasts: " + t);
            }
            IntentFilter filter = new IntentFilter(VirtualWindowClient.ACTION_OPEN);
            filter.addAction(VirtualWindowClient.ACTION_CLOSE_ALL);
            if (Build.VERSION.SDK_INT >= 33) {
                sContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                sContext.registerReceiver(receiver, filter);
            }
            sRegistered = true;
            XposedBridge.log(TAG + ": ready, moduleUid=" + sModuleUid
                    + " display=" + (display == null ? "null" : display.getDisplayId()));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": init failed " + t);
            Log.e(TAG, "init failed", t);
        }
    }

    private static final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            try {
                if (intent == null) return;
                if (VirtualWindowClient.ACTION_CLOSE_ALL.equals(intent.getAction())) {
                    closeAll();
                    return;
                }
                if (!VirtualWindowClient.ACTION_OPEN.equals(intent.getAction())) return;
                if (!isTrustedSender()) {
                    XposedBridge.log(TAG + ": rejected sender uid=" + android.os.Binder.getCallingUid());
                    return;
                }
                ComponentName component = intent.getParcelableExtra(
                        VirtualWindowClient.EXTRA_COMPONENT);
                int taskId = intent.getIntExtra(VirtualWindowClient.EXTRA_TASK_ID, 0);
                int userId = intent.getIntExtra(VirtualWindowClient.EXTRA_USER_ID, 0);
                String source = intent.getStringExtra(VirtualWindowClient.EXTRA_SOURCE);
                open(component, taskId, userId, source);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": onReceive failed " + t);
                Log.e(TAG, "onReceive failed", t);
            }
        }
    };

    private static boolean isTrustedSender() {
        int uid = android.os.Binder.getCallingUid();
        if (uid == 1000 || uid == 0) return true;
        if (sModuleUid <= 0) return true;
        return uid == sModuleUid;
    }

    private static void open(ComponentName component, int taskId, int userId, String source) {
        if (sContext == null) {
            XposedBridge.log(TAG + ": open before init");
            return;
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                VirtualWindow existing = findExisting(component, taskId);
                if (existing != null) {
                    bringToTop(existing);
                    existing.activate();
                    XposedBridge.log(TAG + ": reuse existing window display=" + existing.displayId());
                    return;
                }
                VirtualWindow window = new VirtualWindow(
                        sWindowContext, sWindowManager, sDisplayManager,
                        sActivityTaskManager, component, taskId, userId);
                sWindows.add(window);
                window.show();
                XposedBridge.log(TAG + ": window created for "
                        + (component == null ? ("task " + taskId) : component.flattenToShortString())
                        + " display=" + window.displayId());
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": create window failed " + t);
                Log.e(TAG, "create window failed", t);
            }
        });
    }

    private static VirtualWindow findExisting(ComponentName component, int taskId) {
        String pkg = component == null ? null : component.getPackageName();
        for (VirtualWindow w : sWindows) {
            if (taskId > 0 && w.matchesTask(taskId)) return w;
            if (pkg != null && Objects.equals(pkg, w.packageKey())) return w;
        }
        return null;
    }

    static void bringToTop(VirtualWindow window) {
        sWindows.remove(window);
        sWindows.add(window);
        window.bringToTop();
    }

    static void remove(VirtualWindow window) {
        sWindows.remove(window);
    }

    private static void closeAll() {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (VirtualWindow w : new ArrayList<>(sWindows)) w.close();
        });
    }

    private static Object atm() throws Exception {
        Class<?> cls = Class.forName("android.app.ActivityTaskManager");
        Method m = cls.getMethod("getService");
        return m.invoke(null);
    }
}
