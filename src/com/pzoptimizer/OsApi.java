package com.pzoptimizer;

/**
 * The OS calls PZONative makes, for when pzo_native64.dll isn't loaded (Smart App Control, missing file). WinApi does
 * them through the Java 22+ foreign function API straight into the signed Windows DLLs, so nothing unsigned loads.
 * Mirrors the matching functions in native/pzo_native.c.
 */
interface OsApi {
    boolean setTimerResolution(boolean enable);
    int timerResolution100ns();
    boolean disablePowerThrottling();
    boolean setMmcss(String profile);
    /** 0 normal, 1 above normal, 2 high. */
    boolean setPriorityClass(int level);
    boolean lockWorkingSet();
    boolean optimizeCallingThread(int priorityLevel, boolean bindPCores, String mmcssProfile);
    boolean bindCallingThreadToPCores();
    int physicalCores();
    int performanceCores();
    int logicalProcessors();
    long performanceCoreMask();
    boolean isSolidStateDrive(String path);
}
