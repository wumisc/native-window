package io.github.wumisc.nativewindow.core;

/** 对系统发起的退出请求，模块要做什么。 */
public enum CloseDecision {
    /** 不干预，保持系统原行为。 */
    KEEP_ORIGINAL,

    /** 把“收成贴边浮标”换成退后台/关闭，并隐藏任务 surface。 */
    REPLACE_WITH_TO_BACK
}
