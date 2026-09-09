package com.pzoptimizer;

public final class EngineGLStateGovernor {

    private static volatile int activeTextureId = -1;
    private static volatile int activeProgramId = -1;
    private static volatile int activeBlendSrc = -1;
    private static volatile int activeBlendDst = -1;
    private static volatile boolean blendEnabled = false;

    public static void initialize() {
        reset();
        PZOLogger.success("EngineGLStateGovernor: OpenGL L1 Shadow-State Register Matrix Armed");
    }

    public static boolean shouldBindTexture(int textureId) {
        if (textureId == activeTextureId) {
            return false;
        }
        activeTextureId = textureId;
        return true;
    }

    public static boolean shouldSetProgram(int programId) {
        if (programId == activeProgramId) {
            return false;
        }
        activeProgramId = programId;
        return true;
    }

    public static boolean shouldSetBlendFunc(int src, int dst) {
        if (src == activeBlendSrc && dst == activeBlendDst) {
            return false;
        }
        activeBlendSrc = src;
        activeBlendDst = dst;
        return true;
    }

    public static void reset() {
        activeTextureId = -1;
        activeProgramId = -1;
        activeBlendSrc = -1;
        activeBlendDst = -1;
        blendEnabled = false;
    }
}
