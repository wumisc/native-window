package io.github.wumisc.nativewindow.core;

/** 目标包在屏幕上的可见状态，供启动裁决使用。与 {@link WindowForm} 会话状态不同。 */
public enum TargetWindowForm {
    /** 没有可复用任务。 */
    NONE,

    /** 已有可见窗口：全屏或小窗，都只前置，不新建。 */
    VISIBLE_WINDOW,

    /** 浮动场景但不可见：迷你窗、贴边挂起、退出中。 */
    FLEXIBLE_HIDDEN,

    /** 后台普通全屏任务，需要按小窗启动或移动。 */
    BACKGROUND_TASK
}
