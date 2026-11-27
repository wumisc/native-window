package io.github.wumisc.nativewindow.core;

/**
 * 启动裁决。规则不直接调用隐藏 API，便于后续 JVM 单测。
 */
public final class LaunchPolicy {
    private LaunchPolicy() {}

    /**
     * @param form 当前任务状态
     * @param vendorToggleAvailable 厂商系统是否提供可靠的“隐藏浮动窗恢复”接口；
     *                               AOSP 虚拟屏路线默认 false，回退到启动/移动路径
     */
    public static FanLaunchAction decide(TargetWindowForm form, boolean vendorToggleAvailable) {
        if (form == null) return FanLaunchAction.LAUNCH_AS_ZOOM;
        switch (form) {
            case VISIBLE_WINDOW:
                return FanLaunchAction.FRONT_EXISTING;
            case FLEXIBLE_HIDDEN:
                return vendorToggleAvailable
                        ? FanLaunchAction.TOGGLE_FLEXIBLE
                        : FanLaunchAction.LAUNCH_AS_ZOOM;
            case BACKGROUND_TASK:
            case NONE:
            default:
                return FanLaunchAction.LAUNCH_AS_ZOOM;
        }
    }
}
