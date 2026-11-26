package io.github.wumisc.nativewindow;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class OverlayService extends Service {
    public static final String ACTION_SHOW = "io.github.wumisc.nativewindow.SHOW";
    public static final String ACTION_STOP = "io.github.wumisc.nativewindow.STOP";
    public static final String EXTRA_MODE = "mode";

    private DemoOverlay overlay;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            hide();
            stopSelf();
            return START_NOT_STICKY;
        }
        String mode = intent != null ? intent.getStringExtra(EXTRA_MODE) : "window";
        if (overlay == null) overlay = new DemoOverlay(this);
        overlay.show(mode == null ? "window" : mode);
        return START_STICKY;
    }

    private void hide() {
        if (overlay != null) {
            overlay.hide();
            overlay = null;
        }
    }

    @Override
    public void onDestroy() {
        hide();
        super.onDestroy();
    }
}
