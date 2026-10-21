package com.pzoptimizer;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

/**
 * With "Lock cursor to window", PZ draws a software cursor sampled at the start of the simulation frame,
 * so it trails the real mouse by 15-26 ms under VSync or heavy GPU load. The cursor sprite's transform is
 * captured and, just before present on the render thread, moved to the latest pointer position.
 */
public final class CursorLateLatch {

    private static volatile boolean active = true;
    public static final AtomicLong framesLatched = new AtomicLong(0);

    private static volatile int recordedX = 0;
    private static volatile int recordedY = 0;

    private static Method getCursorPosMethod = null;
    private static Method getWindowHandleMethod = null;
    private static long windowHandle = 0L;
    private static boolean reflectionInit = false;

    private static synchronized void initReflection() {
        if (reflectionInit) return;
        try {
            Class<?> displayClass = Class.forName("org.lwjglx.opengl.Display");
            try {
                getWindowHandleMethod = displayClass.getMethod("getWindow");
                Object h = getWindowHandleMethod.invoke(null);
                if (h instanceof Number) {
                    windowHandle = ((Number) h).longValue();
                }
            } catch (Throwable ignored) {}

            Class<?> glfwClass = Class.forName("org.lwjgl.glfw.GLFW");
            getCursorPosMethod = glfwClass.getMethod("glfwGetCursorPos", long.class, double[].class, double[].class);
        } catch (Throwable ignored) {}
        reflectionInit = true;
    }

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean value) {
        active = value;
    }

    /**
     * Called on the game simulation thread when the cursor sprite is queued.
     */
    public static void recordCursorPosition(int x, int y) {
        if (!active) return;
        recordedX = x;
        recordedY = y;
    }

    /** Offset from the queued cursor position to the newest pointer position, as [dx, dy]. */
    public static float[] calculateLateLatchDelta(float currentX, float currentY) {
        if (!active) {
            return new float[] {0.0f, 0.0f};
        }

        initReflection();
        double[] xpos = new double[1];
        double[] ypos = new double[1];

        try {
            if (getCursorPosMethod != null && windowHandle != 0L) {
                getCursorPosMethod.invoke(null, windowHandle, xpos, ypos);
                float freshX = (float) xpos[0];
                float freshY = (float) ypos[0];

                float dx = freshX - recordedX;
                float dy = freshY - recordedY;

                // Clamp extreme anomalous jumps (> 300px)
                if (Math.abs(dx) < 300.0f && Math.abs(dy) < 300.0f) {
                    framesLatched.incrementAndGet();
                    return new float[] {dx, dy};
                }
            }
        } catch (Throwable ignored) {}

        return new float[] {0.0f, 0.0f};
    }
}
