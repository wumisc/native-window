package io.github.wumisc.nativewindow.core;

/** 松手/点击目标后应执行的动作。 */
public enum FanLaunchAction {
    /** 已有可见任务：前置，不重启、不新建。 */
    FRONT_EXISTING,

    /** 已有隐藏浮动窗：请求系统切回小窗。 */
    TOGGLE_FLEXIBLE,

    /** 新开或以小窗方式拉起后台/隐藏任务。 */
    LAUNCH_AS_ZOOM
}
