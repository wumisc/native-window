package io.github.wumisc.nativewindow.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import io.github.wumisc.nativewindow.chrome.TaskChromeService;

/**
 * 最近任务菜单的“小窗”动作落点。
 *
 * 开窗本身在 Launcher 进程完成（Launcher 持有 START_TASKS_FROM_RECENTS）；
 * 这里只负责把模块 App 的 chrome 服务拉起来。这样即使 App 进程被杀，
 * 也不会出现“点了菜单但开不了窗”的假闭环。
 */
public class OpenReceiver extends BroadcastReceiver {
    public static final String ACTION_OPEN_SMALL_WINDOW =
            "io.github.wumisc.nativewindow.action.OPEN_SMALL_WINDOW";
    public static final String EXTRA_TASK_ID = "task_id";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_OPEN_SMALL_WINDOW.equals(intent.getAction())) return;
        int taskId = intent.getIntExtra(EXTRA_TASK_ID, -1);
        if (taskId <= 0) return;

        Intent service = new Intent(context, TaskChromeService.class)
                .setAction(TaskChromeService.ACTION_START)
                .putExtra(TaskChromeService.EXTRA_TASK_ID, taskId);
        try {
            context.startForegroundService(service);
        } catch (Throwable t) {
            try {
                context.startService(service);
            } catch (Throwable t2) {
                Toast.makeText(context, "小窗控件启动失败，请查看 LSPosed 日志",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }
}
