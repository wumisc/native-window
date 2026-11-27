package io.github.wumisc.nativewindow.hook;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * HyperOS 4 的 SystemUI 入口。
 *
 * HyperOS 4 的 freeform 顶栏不是 AOSP 的 CaptionWindowDecoration，而是
 * com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor 下的
 * MiuiBaseWindowDecoration / MiuiTopDecoration / MiuiDecorationDot。
 * 这里 hook createTopCaption，在它返回的顶层 View 里挂我们的胶囊；类名不存在
 * 就退回 AOSP 路径，绝不让 SystemUI 起不来。
 */
public final class HyperOsSystemUiHook {
    private static final String TAG = "NativeWindow/HyperOS-SUI";

    private HyperOsSystemUiHook() {}

    public static void install(XC_LoadPackage.LoadPackageParam lp) {
        if (!HyperOsDetect.isHyperOs(lp.classLoader)) return;
        // HyperOS 4 的顶栏实际是 MiuiTopDecoration；HyperOS 3 及更早才是
        // MiuiBaseWindowDecoration。两个都试，哪个存在挂哪个。
        String[] candidates = new String[]{
                "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.MiuiTopDecoration",
                "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration.MiuiDecorationDot",
                "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.MiuiBaseWindowDecoration",
                "com.android.wm.shell.miuimultiwinswitch.miuiwindowdecor.MiuiBaseWindowDecoration",
        };
        int hooked = 0;
        for (String name : candidates) {
            Class<?> cls = XposedHelpers.findClassIfExists(name, lp.classLoader);
            if (cls == null) {
                XposedBridge.log(TAG + ": missing " + name);
                continue;
            }
            XposedBridge.log(TAG + ": found " + name);
            try {
                XposedBridge.hookAllMethods(cls, "createTopCaption", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (param.getResult() instanceof View) {
                                HyperOsCaptionDecorator.decorate(
                                        param.thisObject, (View) param.getResult());
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": decorate failed " + t);
                        }
                    }
                });
                hooked++;
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": hook " + name + " failed " + t);
            }
        }
        if (hooked == 0) {
            XposedBridge.log(TAG + ": no HyperOS top caption class found");
        } else {
            XposedBridge.log(TAG + ": HyperOS top caption hooked (" + hooked + ")");
        }
    }

    private static Class<?> first(ClassLoader cl, String... names) {
        for (String name : names) {
            Class<?> c = XposedHelpers.findClassIfExists(name, cl);
            if (c != null) return c;
        }
        return null;
    }
}
