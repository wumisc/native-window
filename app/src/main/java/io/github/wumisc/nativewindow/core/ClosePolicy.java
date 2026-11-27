package io.github.wumisc.nativewindow.core;

/**
 * 退出裁决。只处理“系统裁决要收成浮标”的场景；用户手动拖到边缘不在这里干预。
 */
public final class ClosePolicy {
    private ClosePolicy() {}

    public static CloseDecision decide(boolean autoCloseEnabled,
                                      boolean isFloatExitStrategy,
                                      boolean fromSystemVerdict) {
        if (autoCloseEnabled && isFloatExitStrategy && fromSystemVerdict) {
            return CloseDecision.REPLACE_WITH_TO_BACK;
        }
        return CloseDecision.KEEP_ORIGINAL;
    }
}
