package io.github.wumisc.nativewindow.anim;

import android.view.Choreographer;

/**
 * 自研物理弹簧：不依赖 ViewPropertyAnimator / 系统 Interpolator。
 * 半隐式欧拉 + 子步进，保证在 60/90/120/144Hz 下一致。
 */
public final class SpringAnimator implements Choreographer.FrameCallback {
    public interface Property { void set(float value); }

    private final Property property;
    private final float stiffness;
    private final float damping;
    private final float mass;

    private float value;
    private float target;
    private float velocity;
    private long lastFrameNanos;
    private boolean running;

    public SpringAnimator(float stiffness, float damping, float mass, Property property) {
        this.stiffness = stiffness;
        this.damping = damping;
        this.mass = Math.max(0.001f, mass);
        this.property = property;
    }

    public SpringAnimator start(float from, float to) {
        this.value = from;
        this.target = to;
        this.velocity = 0f;
        this.lastFrameNanos = 0L;
        this.running = true;
        property.set(from);
        Choreographer.getInstance().removeFrameCallback(this);
        Choreographer.getInstance().postFrameCallback(this);
        return this;
    }

    public SpringAnimator fromVelocity(float from, float to, float initialVelocity) {
        start(from, to);
        this.velocity = initialVelocity;
        return this;
    }

    public void cancel() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
    }

    public float getValue() { return value; }
    public float getTarget() { return target; }
    public boolean isRunning() { return running; }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!running) return;
        if (lastFrameNanos == 0L) lastFrameNanos = frameTimeNanos;
        float dt = (frameTimeNanos - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = frameTimeNanos;
        if (dt > 0.032f) dt = 0.032f;
        if (dt <= 0f) {
            Choreographer.getInstance().postFrameCallback(this);
            return;
        }

        // 4 个子步，避免高刚度在低帧率下炸开
        int steps = 4;
        float h = dt / steps;
        for (int i = 0; i < steps; i++) {
            float springForce = -stiffness * (value - target);
            float dampingForce = -damping * velocity;
            float acceleration = (springForce + dampingForce) / mass;
            velocity += acceleration * h;
            value += velocity * h;
        }

        property.set(value);

        if (Math.abs(velocity) < 0.05f && Math.abs(value - target) < 0.5f) {
            value = target;
            velocity = 0f;
            property.set(value);
            running = false;
            return;
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    /** 窗口开启：轻微过冲后收住，接近 HyperOS 的“有重量”感。 */
    public static SpringAnimator windowOpen(Property p) {
        return new SpringAnimator(420f, 34f, 1f, p);
    }

    /** 迷你/贴边：更快、更硬，避免拖泥带水。 */
    public static SpringAnimator snap(Property p) {
        return new SpringAnimator(560f, 38f, 1f, p);
    }

    /** 扇形：柔和展开，允许一点回弹。 */
    public static SpringAnimator radial(Property p) {
        return new SpringAnimator(360f, 28f, 1f, p);
    }
}
