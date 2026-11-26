package io.github.wumisc.nativewindow.core;

import android.content.ComponentName;
import android.graphics.Rect;

/** 所有入口（启动器/最近任务/扇形/侧栏/QS）统一成这一个请求。 */
public final class SmallWindowRequest {
    public final String source;
    public final String packageName;
    public final ComponentName component;
    public final int taskId;
    public final int userId;
    public final int displayId;
    public final WindowForm form;
    public final Rect bounds;

    public SmallWindowRequest(String source, String packageName, ComponentName component,
                              int taskId, int userId, int displayId,
                              WindowForm form, Rect bounds) {
        this.source = source;
        this.packageName = packageName;
        this.component = component;
        this.taskId = taskId;
        this.userId = userId;
        this.displayId = displayId;
        this.form = form;
        this.bounds = bounds;
    }
}
