package io.github.wumisc.nativewindow.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** 最近任务菜单的“小窗”动作落点；后续可换成 system_server 签名 Binder。 */
public class OpenReceiver extends BroadcastReceiver {
    public static final String ACTION_OPEN_SMALL_WINDOW =
            "io.github.wumisc.nativewindow.action.OPEN_SMALL_WINDOW";
    public static final String EXTRA_TASK_ID = "task_id";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_OPEN_SMALL_WINDOW.equals(intent.getAction())) return;
        int taskId = intent.getIntExtra(EXTRA_TASK_ID, -1);
        if (taskId <= 0) return;
        boolean ok = SmallWindowBridge.openFromTask(context, taskId);
        if (!ok) Toast.makeText(context, "小窗启动失败，请查看 LSPosed 日志", Toast.LENGTH_SHORT).show();
    }
}
