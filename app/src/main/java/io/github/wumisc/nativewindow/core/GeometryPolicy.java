package io.github.wumisc.nativewindow.core;

/** 纯几何规则：尺寸、越界、贴边阈值。不依赖 Android Rect/View。 */
public final class GeometryPolicy {
    public static final int SIDE_NONE = 0;
    public static final int SIDE_LEFT = -1;
    public static final int SIDE_RIGHT = 1;

    private GeometryPolicy() {}

    public static int clamp(int value, int min, int max) {
        if (max < min) max = min;
        return Math.max(min, Math.min(max, value));
    }

    public static int safeX(int x, int windowWidth, int screenWidth, int margin) {
        return clamp(x, -margin, Math.max(0, screenWidth - windowWidth + margin));
    }

    public static int safeY(int y, int windowHeight, int screenHeight, int margin) {
        return clamp(y, -margin, Math.max(0, screenHeight - windowHeight + margin));
    }

    /** 拖动结束后按边缘阈值判断是否贴边；未进入阈值返回 {@link #SIDE_NONE}。 */
    public static int snapSide(int x, int windowWidth, int screenWidth, int threshold) {
        if (x <= threshold) return SIDE_LEFT;
        if (x + windowWidth >= screenWidth - threshold) return SIDE_RIGHT;
        return SIDE_NONE;
    }

    /** 贴边后保留在屏幕内的把手宽度。 */
    public static int edgeHandleWidth(int fullWidth, int visibleRatio) {
        return Math.max(24, Math.round(fullWidth * Math.max(0.05f, Math.min(0.5f, visibleRatio))));
    }
}
