package com.pzoptimizer;

/** Shadow copies of the bound texture, shader program and blend state, used to skip redundant GL calls. */
public final class EngineGLStateGovernor {

    private static volatile int activeTextureId = -1;
    private static volatile int activeProgramId = -1;
    private static volatile int activeBlendSrc = -1;
    private static volatile int activeBlendDst = -1;
    private static volatile boolean blendEnabled = false;

    // Mobile / GL4ES Environment Detection (Zomdroid)
    private static volatile boolean mobileGLEnvironment = false;

    static {
        try {
            String glLib = System.getenv("LIBGL_ES");
            String zomdroidProp = System.getProperty("zomboid.zomdroid");
            String pzoMobileProp = System.getProperty("pzoptimizer.mobile");
            if ("true".equalsIgnoreCase(zomdroidProp) || "true".equalsIgnoreCase(pzoMobileProp) ||
                (glLib != null && !glLib.isEmpty()) || System.getenv("ZOMDROID") != null ||
                new java.io.File("/storage/emulated/0").exists()) {
                mobileGLEnvironment = true;
            }
        } catch (Throwable ignored) {}
    }

    public static boolean isMobileGLEnvironment() {
        return mobileGLEnvironment;
    }

    public static void setMobileGLEnvironment(boolean mobile) {
        mobileGLEnvironment = mobile;
    }

    public static void initialize() {
        reset();
        if (mobileGLEnvironment) {
            PZOLogger.info("[EngineGLStateGovernor] Mobile / GL4ES execution environment detected (Zomdroid)");
        }
        PZOLogger.success("EngineGLStateGovernor: OpenGL L1 Shadow-State Register Matrix Armed");
    }

    public static boolean shouldBindTexture(int textureId) {
        if (textureId == activeTextureId) {
            return false; // Skip redundant JNI bind
        }
        activeTextureId = textureId;
        return true;
    }

    public static boolean shouldSetProgram(int programId) {
        if (programId == activeProgramId) {
            return false; // Skip redundant shader use
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
