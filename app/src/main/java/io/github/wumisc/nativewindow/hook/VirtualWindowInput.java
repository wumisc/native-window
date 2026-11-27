package io.github.wumisc.nativewindow.hook;

import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/** 把悬浮卡片上的触摸/按键按虚拟屏 displayId 注入系统输入管线。 */
final class VirtualWindowInput {
    private static final String TAG = "NativeWindow/VWI";

    private final Object inputManager;
    private final Method injectInputEvent;
    private final Method setDisplayId;

    VirtualWindowInput() {
        Object im = null;
        Method inject = null;
        Method display = null;
        try {
            Object binder = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class)
                    .invoke(null, "input");
            Class<?> stub = Class.forName("android.hardware.input.IInputManager$Stub");
            im = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
        } catch (Throwable t) {
            Log.e(TAG, "get input manager failed", t);
        }
        if (im != null) {
            for (Method m : im.getClass().getMethods()) {
                if ("injectInputEvent".equals(m.getName()) && m.getParameterTypes().length == 2
                        && m.getParameterTypes()[0] == InputEvent.class) {
                    inject = m;
                    break;
                }
            }
        }
        try {
            display = Class.forName("android.view.InputEvent")
                    .getDeclaredMethod("setDisplayId", int.class);
            display.setAccessible(true);
        } catch (Throwable t) {
            Log.e(TAG, "setDisplayId not found", t);
        }
        inputManager = im;
        injectInputEvent = inject;
        setDisplayId = display;
    }

    boolean ready() {
        return inputManager != null && injectInputEvent != null && setDisplayId != null;
    }

    void key(int keyCode, int displayId) {
        if (!ready()) return;
        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0);
        down.setSource(InputDevice.SOURCE_KEYBOARD);
        KeyEvent up = new KeyEvent(now, now + 20, KeyEvent.ACTION_UP, keyCode, 0);
        up.setSource(InputDevice.SOURCE_KEYBOARD);
        inject(down, displayId);
        inject(up, displayId);
    }

    void motion(MotionEvent event, int displayId) {
        if (!ready()) return;
        try {
            int count = event.getPointerCount();
            MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
            MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[count];
            for (int i = 0; i < count; i++) {
                MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
                MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
                event.getPointerCoords(i, c);
                event.getPointerProperties(i, p);
                coords[i] = c;
                props[i] = p;
            }
            MotionEvent copy = MotionEvent.obtain(
                    event.getDownTime(), event.getEventTime(), event.getAction(), count,
                    props, coords, event.getMetaState(), event.getButtonState(),
                    event.getXPrecision(), event.getYPrecision(), event.getDeviceId(),
                    event.getEdgeFlags(), event.getSource(), event.getFlags());
            inject(copy, displayId);
            copy.recycle();
        } catch (Throwable t) {
            Log.e(TAG, "motion failed", t);
        }
    }

    private void inject(InputEvent event, int displayId) {
        try {
            setDisplayId.invoke(event, displayId);
            injectInputEvent.invoke(inputManager, event, 0);
        } catch (Throwable t) {
            Log.e(TAG, "inject failed", t);
        }
    }
}
