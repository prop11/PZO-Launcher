package com.pzoptimizer;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OpenGL Pipeline Stall & GL State Query Governor.
 * Calling driver query functions like `glGetInteger(GL_CURRENT_PROGRAM)` forces the GPU
 * command buffer to drain and flushes the graphics pipeline, stalling the render thread.
 * In weather and particle passes (`WeatherParticleDrawer`), this query is invoked frequently.
 * 
 * GLPipelineGovernor intercepts current program checks and reads tracked shader state from
 * `ShaderHelper.currentlyBound` without issuing blocking driver queries.
 */
public final class GLPipelineGovernor {

    private static volatile boolean active = true;
    public static final AtomicLong pipelineStallsAvoided = new AtomicLong(0);

    private static final VarHandle BOUND_PROGRAM_HANDLE;
    private static Method glGetIntegerMethod = null;
    private static boolean glInitAttempted = false;

    static {
        VarHandle handle = null;
        try {
            Class<?> shaderHelperClass = Class.forName("zombie.core.ShaderHelper");
            handle = MethodHandles.privateLookupIn(shaderHelperClass, MethodHandles.lookup())
                    .findStaticVarHandle(shaderHelperClass, "currentlyBound", int.class);
        } catch (Throwable ignored) {}
        BOUND_PROGRAM_HANDLE = handle;
    }

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean value) {
        active = value;
    }

    /**
     * Obtains the active GL shader program without triggering a GPU pipeline stall.
     */
    public static int getCurrentProgram() {
        if (!active || BOUND_PROGRAM_HANDLE == null) {
            return queryDriverProgram();
        }

        try {
            int bound = (int) BOUND_PROGRAM_HANDLE.get();
            if (bound > 0) {
                pipelineStallsAvoided.incrementAndGet();
                return bound;
            }
        } catch (Throwable ignored) {}

        return queryDriverProgram();
    }

    private static int queryDriverProgram() {
        if (!glInitAttempted) {
            glInitAttempted = true;
            try {
                Class<?> glCls = Class.forName("org.lwjgl.opengl.GL11");
                glGetIntegerMethod = glCls.getMethod("glGetInteger", int.class);
            } catch (Throwable ignored) {}
        }
        try {
            if (glGetIntegerMethod != null) {
                // GL_CURRENT_PROGRAM = 0x8B8D (35725)
                return ((Number) glGetIntegerMethod.invoke(null, 35725)).intValue();
            }
        } catch (Throwable ignored) {}
        return 0;
    }
}
