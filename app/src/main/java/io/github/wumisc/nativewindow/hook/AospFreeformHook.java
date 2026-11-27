package io.github.wumisc.nativewindow.hook;

import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * AOSP 17（PixelOS / LineageOS）的“带标记启动”入口。
 *
 * HyperOS 有 MiuiMultiWindowUtils，AOSP 没有；但 ActivityStarter 是共用的：
 * - request.intent 带 nativewindow.extra.FREEFORM；
 * - 直接给 ActivityStarter 塞 Bundle，写死 windowingMode=5 + launchBounds。
 *
 * 这样模块 App 的“选择应用开小窗”在类原生上也能走同一条路，不再依赖 MIUI 私有 API。
 */
public final class AospFreeformHook {
    private static final String TAG = "NativeWindow/AOSP-SS";
    private static final String EXTRA_FREEFORM =
            "io.github.wumisc.nativewindow.extra.FREEFORM";
    private static final String EXTRA_BOUNDS =
            "io.github.wumisc.nativewindow.extra.BOUNDS";

    private AospFreeformHook() {}

    public static void install(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> starter = XposedHelpers.findClassIfExists(
                    "com.android.server.wm.ActivityStarter", lp.classLoader);
            if (starter == null) return;
            XposedBridge.hookAllMethods(starter, "executeRequest", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length < 1) return;
                        Object request = param.args[0];
                        Object intentObj = XposedHelpers.getObjectField(request, "intent");
                        if (!(intentObj instanceof Intent)) return;
                        Intent intent = (Intent) intentObj;
                        if (!intent.getBooleanExtra(EXTRA_FREEFORM, false)) return;

                        Object service = XposedHelpers.getObjectField(
                                param.thisObject, "mService");
                        Object context = XposedHelpers.getObjectField(service, "mContext");
                        Rect bounds = intent.getParcelableExtra(EXTRA_BOUNDS);
                        if (bounds == null) {
                            int w = 1080, h = 2400;
                            try {
                                android.view.WindowManager wm =
                                        (android.view.WindowManager)
                                                ((android.content.Context) context)
                                                        .getSystemService(
                                                                android.content.Context.WINDOW_SERVICE);
                                Rect full = new Rect(wm.getMaximumWindowMetrics().getBounds());
                                w = full.width();
                                h = full.height();
                            } catch (Throwable ignored) {}
                            bounds = new Rect((int) (w * 0.06f), (int) (h * 0.14f),
                                    (int) (w * 0.94f), (int) (h * 0.78f));
                        }
                        Bundle b = new Bundle();
                        b.putInt(SmallWindowBridge.KEY_LAUNCH_WINDOWING_MODE,
                                SmallWindowBridge.WINDOWING_MODE_FREEFORM);
                        b.putParcelable(SmallWindowBridge.KEY_LAUNCH_BOUNDS, bounds);
                        XposedHelpers.callMethod(param.thisObject,
                                "setActivityOptions", b);
                        XposedBridge.log(TAG + ": tagged executeRequest -> freeform "
                                + bounds.flattenToString());
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": tagged freeform failed " + t);
                    }
                }
            });
            XposedBridge.log(TAG + ": ActivityStarter.executeRequest hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": install failed " + t);
        }
    }
}
