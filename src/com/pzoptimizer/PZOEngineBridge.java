package com.pzoptimizer;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Exposes PZO engine status, diagnostics and options to Lua through LuaManager.env. */
public class PZOEngineBridge {

    private static volatile boolean initialized = false;
    private static volatile int cachedRamGb = 0;

    public static boolean isEnginePresent() {
        return true;
    }

    public static boolean isActive() {
        return true;
    }

    public static boolean isWindowActive() {
        try {
            Class<?> displayCls = Class.forName("org.lwjglx.opengl.Display");
            Method isActiveMethod = displayCls.getMethod("isActive");
            return (boolean) isActiveMethod.invoke(null);
        } catch (Throwable t1) {
            try {
                Class<?> displayCls = Class.forName("org.lwjgl.opengl.Display");
                Method isActiveMethod = displayCls.getMethod("isActive");
                return (boolean) isActiveMethod.invoke(null);
            } catch (Throwable ignored) {}
        }
        return true;
    }

    public static int getOptimizedRAM() {
        if (cachedRamGb <= 0) {
            long maxMemMB = Runtime.getRuntime().maxMemory() / (1024 * 1024);
            cachedRamGb = Math.max(2, (int) Math.round(maxMemMB / 1024.0));
        }
        return cachedRamGb;
    }

    public static String getVersion() {
        return UpdateChecker.CURRENT_VERSION;
    }

    public static String getChannel() {
        return PZOConfig.isBetaOptIn() ? "Unstable / Beta" : "Stable";
    }

    public static boolean isG1GC() {
        return true;
    }

    public static boolean isZomdroid() {
        return EngineGLStateGovernor.isMobileGLEnvironment();
    }

    public static boolean isNativeGovernorActive() {
        return PZONative.isLoaded();
    }

    public static int getPerformanceCores() {
        return PZONative.isLoaded() ? PZONative.getPerformanceCores() : Runtime.getRuntime().availableProcessors();
    }

    public static double getTimerResolutionMs() {
        return PZONative.isLoaded() ? (PZONative.getTimerResolution100ns() / 10000.0) : 15.6;
    }

    public static boolean isAVX2Active() {
        return PZONative.isLoaded() && PZONative.isAVX2Supported();
    }

    public static boolean isAVX2SpatialActive() {
        return PZONative.isLoaded() && PZONative.isAVX2Supported();
    }

    public static int getHordeZombiesTracked() {
        return HordeSpatialCuller.lastTrackedZombieCount.get();
    }

    public static int getHordeCulledOffscreen() {
        return HordeSpatialCuller.lastCulledOffscreenCount.get();
    }

    public static long getBoneTransformsSaved() {
        return com.pzoptimizer.multicore.PZOMultiCoreEngine.getBonesSaved();
    }

    public static boolean isMultiCoreActive() {
        return com.pzoptimizer.multicore.PZOMultiCoreEngine.isMultiCoreActive();
    }

    public static int getMultiCoreWorkers() {
        return com.pzoptimizer.multicore.PZOMultiCoreEngine.getWorkerCount();
    }

    public static long getParallelChunksStreamed() {
        return com.pzoptimizer.multicore.PZOMultiCoreEngine.getParallelChunksStreamed();
    }

    public static long getParallelHordeSweeps() {
        return com.pzoptimizer.multicore.PZOMultiCoreEngine.getParallelHordeSweeps();
    }

    public static long getParallelSimulatedEntities() {
        return com.pzoptimizer.multicore.PZOMultiCoreEngine.getParallelSimulatedEntities();
    }

    public static int getDetectedMonitorHz() {
        return VRRDisplayGovernor.getDetectedHz();
    }

    public static int getTargetVRRCap() {
        return VRRDisplayGovernor.getTargetFrameCap();
    }

    public static long getFramesUpscaled() {
        return WorldUpscalerGovernor.framesUpscaled.get();
    }

    public static long getPackPagesScanned() {
        return PackPageScanner.pagesScanned.get();
    }

    public static long getPackBytesScanned() {
        return PackPageScanner.bytesScanned.get();
    }

    public static boolean isBetaOptIn() {
        return PZOConfig.isBetaOptIn();
    }

    public static void setBetaOptIn(boolean optIn) {
        PZOConfig.setBetaOptIn(optIn);
    }

    public static boolean isMultithreadingNoticeAcknowledged() {
        return PZOConfig.isMultithreadingNoticeAcknowledged();
    }

    public static boolean isWorldUpscalerActive() {
        return WorldUpscalerGovernor.isActive();
    }

    public static double getWorldUpscalerRenderScale() {
        return WorldUpscalerGovernor.getRenderScale();
    }

    public static double getWorldUpscalerSharpness() {
        return WorldUpscalerGovernor.getSharpness();
    }

    public static boolean isViewpointStanddownActive() {
        return ViewpointStanddownGovernor.isStanddownActive();
    }

    public static boolean isViewpointDetected() {
        return ViewpointStanddownGovernor.isViewpointDetected();
    }

    public static String getViewpointStanddownMode() {
        return ViewpointStanddownGovernor.getStanddownModeString();
    }

    public static void setViewpointStanddownMode(String mode) {
        ViewpointStanddownGovernor.setStanddownMode(mode);
    }

    public static void toggleViewpointStanddown() {
        ViewpointStanddownGovernor.toggleStanddown();
    }

    public static void acknowledgeMultithreadingNotice() {
        PZOConfig.setMultithreadingNoticeAcknowledged(true);
    }

    public static void openWorkshopPage() {
        PZOEntrypoint.openBrowser("https://steamcommunity.com/sharedfiles/filedetails/?id=3787481250");
    }

    public static void openGithubPage() {
        PZOEntrypoint.openBrowser("https://github.com/prop11/PZO-Launcher/issues");
    }

    public static void openBrowser(String url) {
        PZOEntrypoint.openBrowser(url);
    }

    public static void purgeRAM() {
        try {
            System.gc();
            PZOLogger.info("[PZO Bridge] Triggered JVM RAM Purge (System.gc)");
        } catch (Throwable ignored) {}
    }

    public static long getHotSaveCallsThrottled() {
        return HotSaveGovernor.hotSavesSkipped.get();
    }

    public static long getChunkHandoffRecalcsSkipped() {
        return ChunkHandoffRecalcGovernor.getCleanSquaresSkipped();
    }

    public static long getLookupMemoHits() {
        return LookupMemoGovernor.memoHits.sum();
    }

    public static long getPropIdCacheHits() {
        return PropIdCacheGovernor.isSnapshotReady() ? 1L : 0L;
    }

    public static long getRagdollFastFalseReturns() {
        return RagdollGateGovernor.getFastPathChecksSaved();
    }

    public static long getAnimalLosChecksSkipped() {
        return AnimalLosSkipGovernor.getAnimalWalksSkipped();
    }

    public static long getWorkerRandDraws() {
        return WorkerRandGovernor.workerLockFreeDraws.sum();
    }

    public static long getBodyAtlasSpritesPrewarmed() {
        return BodyAtlasPrewarmGovernor.spritesPrewarmed.get();
    }

    public static long getGcSlackNudges() {
        return GcSlackGovernor.slackCollectionsScheduled.get();
    }

    public static boolean isWorldUpscalerDynamicScaleActive() {
        return WorldUpscalerGovernor.isDynamicScaleActive();
    }

    public static void setWorldUpscalerDynamicScaleActive(boolean val) {
        WorldUpscalerGovernor.setDynamicScaleActive(val);
    }

    public static void setJvmOption(String key, boolean value) {
        try {
            if ("JVM_GLStateOptimizer".equals(key)) {
                GLStateOptimizer.setEnabled(value);
            } else if ("JVM_StreamBufferBoost".equals(key)) {
                ChunkBufferPool.setEnabled(value);
            } else if ("JVM_BytecodeBloodCap".equals(key)) {
                PZOptimAgent.setBytecodeBloodCap(value);
            } else if ("JVM_VRRDisplayGovernor".equals(key)) {
                VRRDisplayGovernor.setActive(value);
            } else if ("JVM_WorldUpscaler".equals(key)) {
                WorldUpscalerGovernor.setActive(value);
            } else if ("JVM_WorldUpscalerDynamic".equals(key)) {
                WorldUpscalerGovernor.setDynamicScaleActive(value);
            } else if ("JVM_PackPageScan".equals(key)) {
                PackPageScanner.setActive(value);
            } else if ("JVM_VehicleAnimSkip".equals(key)) {
                VehicleAnimSkipGovernor.setActive(value);
            } else if ("JVM_VehicleLosCull".equals(key)) {
                VehicleLosGovernor.setActive(value);
            } else if ("JVM_HotSaveGovernor".equals(key)) {
                HotSaveGovernor.setActive(value);
            } else if ("JVM_ChunkHandoffRecalc".equals(key)) {
                ChunkHandoffRecalcGovernor.setActive(value);
            } else if ("JVM_LookupMemo".equals(key)) {
                LookupMemoGovernor.setActive(value);
            } else if ("JVM_PropIdCache".equals(key)) {
                PropIdCacheGovernor.setActive(value);
            } else if ("JVM_RagdollGate".equals(key)) {
                RagdollGateGovernor.setActive(value);
            } else if ("JVM_AnimalLosSkip".equals(key)) {
                AnimalLosSkipGovernor.setActive(value);
            } else if ("JVM_WorkerRand".equals(key)) {
                WorkerRandGovernor.setActive(value);
            } else if ("JVM_BodyAtlasPrewarm".equals(key)) {
                BodyAtlasPrewarmGovernor.setActive(value);
            } else if ("JVM_GcSlackGovernor".equals(key)) {
                GcSlackGovernor.setActive(value);
            } else if ("JVM_EngineFramePacer".equals(key)) {
                EngineFramePacer.setEnabled(value);
            } else if ("JVM_PathfindBudget".equals(key)) {
                PathfindGovernor.setBudgetActive(value);
            } else if ("JVM_PathfindSquareMerge".equals(key)) {
                PathfindGovernor.setSquareMerge(value);
            } else if ("JVM_PathfindVehicleThrottle".equals(key)) {
                PathfindGovernor.setVehicleThrottle(value);
            } else if ("JVM_PathfindNativeAStar".equals(key)) {
                PathfindGovernor.setNativeAStar(value);
            } else if ("JVM_LoadJavaMods".equals(key)) {
                PZOConfig.setJavaModsEnabled(value);
            } else if ("JVM_BloodCapPerTile".equals(key)) {
                BloodCapGovernor.setCapEnabled(value);
            }
            PZOLogger.info("[PZO Bridge] setJvmOption: " + key + " = " + value);
        } catch (Throwable ignored) {}
    }

    public static void setJvmIntOption(String key, int value) {
        try {
            if ("JVM_ChunkCacheSize".equals(key)) {
                ChunkIngestionPacer.setMaxCachedChunks(value);
            } else if ("JVM_ChunkBudgetMs".equals(key)) {
                ChunkIngestionPacer.setChunkBudgetMs(value);
            } else if ("JVM_PathfindBudgetMs".equals(key)) {
                PathfindGovernor.setBudgetMs(value);
            } else if ("JVM_BloodMaxPerTile".equals(key)) {
                BloodCapGovernor.setMaxPerTile(value);
            }
            PZOLogger.info("[PZO Bridge] setJvmIntOption: " + key + " = " + value);
        } catch (Throwable ignored) {}
    }

    public static File getZomboidDir() {
        try {
            String prop = System.getProperty("zomboid.cachedir");
            if (prop != null && !prop.trim().isEmpty()) {
                File f = new File(prop.trim());
                if (f.exists()) return f;
            }
        } catch (Throwable ignored) {}
        try {
            String home = System.getProperty("user.home");
            if (home != null) {
                File f = new File(home, "Zomboid");
                if (f.exists()) return f;
            }
        } catch (Throwable ignored) {}
        return new File("Zomboid");
    }

    public static void openLogsFolder() {
        try {
            File zDir = getZomboidDir();
            if (zDir != null && zDir.exists()) {
                if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                    java.awt.Desktop.getDesktop().open(zDir);
                    return;
                }
                String os = System.getProperty("os.name", "").toLowerCase();
                if (os.contains("win")) {
                    Runtime.getRuntime().exec(new String[]{"explorer.exe", zDir.getAbsolutePath()});
                } else if (os.contains("mac")) {
                    Runtime.getRuntime().exec(new String[]{"open", zDir.getAbsolutePath()});
                } else {
                    Runtime.getRuntime().exec(new String[]{"xdg-open", zDir.getAbsolutePath()});
                }
            }
        } catch (Throwable ignored) {}
    }

    public static String getDiagnosticsReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("### Project Zomboid Optimiser - Bug & Crash Diagnostics\n");
        sb.append("- **PZO Engine Version**: ").append(UpdateChecker.CURRENT_VERSION).append("\n");
        sb.append("- **Java Runtime**: ").append(System.getProperty("java.version", "Unknown")).append(" (").append(System.getProperty("os.name", "Unknown")).append(" ").append(System.getProperty("os.arch", "")).append(")\n");
        sb.append("- **Allocated JVM Heap**: ").append(Runtime.getRuntime().maxMemory() / (1024 * 1024)).append(" MB (Used: ").append((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)).append(" MB)\n");
        sb.append("- **CPU Logical Cores**: ").append(Runtime.getRuntime().availableProcessors()).append("\n\n");
        sb.append(EnhancedRenderTelemetry.getTelemetryReport()).append("\n");

        File zDir = getZomboidDir();
        if (zDir != null && zDir.exists()) {
            File pzoLog = new File(zDir, "Lua/pzo_engine.log");
            if (pzoLog.exists()) {
                sb.append("#### `pzo_engine.log`\n```text\n");
                sb.append(readLastLines(pzoLog, 25));
                sb.append("\n```\n\n");
            }
            File consoleTxt = new File(zDir, "console.txt");
            if (consoleTxt.exists()) {
                sb.append("#### `console.txt` (Recent Log Tail & Errors)\n```text\n");
                sb.append(readLastLines(consoleTxt, 50));
                sb.append("\n```\n");
            }
        }
        return sb.toString();
    }

    public static void copyDiagnosticsToClipboard() {
        try {
            String report = getDiagnosticsReport();
            java.awt.datatransfer.StringSelection sel = new java.awt.datatransfer.StringSelection(report);
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, sel);
            PZOLogger.info("[PZO Bridge] Copied system diagnostics and log tails to OS clipboard");
        } catch (Throwable ignored) {}
    }

    public static boolean shouldThrottleDiskWrite(String filename, boolean append) {
        return DiskIOPacer.shouldThrottle(filename, append);
    }

    public static Object getDummyFileWriter() {
        try {
            Method m = DiskIOPacer.class.getMethod("getDummyFileWriter");
            return m.invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String readLastLines(File file, int maxLines) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            long fileLen = raf.length();
            long pos = Math.max(0, fileLen - 16384);
            raf.seek(pos);
            byte[] bytes = new byte[(int) (fileLen - pos)];
            raf.readFully(bytes);
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            String[] lines = text.split("\r?\n");
            if (lines.length <= maxLines) {
                return text.trim();
            }
            StringBuilder sb = new StringBuilder();
            for (int i = lines.length - maxLines; i < lines.length; i++) {
                sb.append(lines[i]).append("\n");
            }
            return sb.toString().trim();
        } catch (Throwable t) {
            return "[Log read notice: " + t.getMessage() + "]";
        }
    }

    /** Waits for zombie.Lua.LuaManager to initialize, then binds the PZO globals. */
    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;

        cachedRamGb = getOptimizedRAM();

        Thread bridgeHookThread = new Thread(() -> {
            boolean attached = false;
            // Poll for LuaManager.env initialization during boot (up to 2.5 minutes for heavy modpacks)
            for (int i = 0; i < 600; i++) {
                try {
                    Class<?> lmClass = Class.forName("zombie.Lua.LuaManager");

                    try {
                        Field exposerField = lmClass.getField("exposer");
                        Object exposer = exposerField.get(null);
                        if (exposer != null) {
                            Method exposeClassMethod = exposer.getClass().getMethod("exposeClass", Class.class);
                            exposeClassMethod.invoke(exposer, PZOEngineBridge.class);
                        }
                    } catch (Throwable ignored) {}

                    Field envField = lmClass.getField("env");
                    Object env = envField.get(null);
                    if (env != null && areLuaEventsRegistered(env)) {
                        Method rawset = env.getClass().getMethod("rawset", Object.class, Object.class);

                        rawset.invoke(env, "PZOEngineActive", Boolean.TRUE);
                        rawset.invoke(env, "isPZOEngineActive", Boolean.TRUE);
                        rawset.invoke(env, "PZOEngineRAM", cachedRamGb);
                        rawset.invoke(env, "PZOEngineVersion", UpdateChecker.CURRENT_VERSION);

                        try {
                            Field platformField = lmClass.getField("platform");
                            Object platform = platformField.get(null);
                            if (platform != null) {
                                Method newTable = platform.getClass().getMethod("newTable");
                                Object pzoTable = newTable.invoke(platform);
                                if (pzoTable != null) {
                                    Method tableRawset = pzoTable.getClass().getMethod("rawset", Object.class, Object.class);
                                    tableRawset.invoke(pzoTable, "active", Boolean.TRUE);
                                    tableRawset.invoke(pzoTable, "ram_gb", cachedRamGb);
                                    tableRawset.invoke(pzoTable, "version", UpdateChecker.CURRENT_VERSION);
                                    tableRawset.invoke(pzoTable, "g1gc", Boolean.TRUE);

                                    try {
                                        Class<?> javaFuncClass = Class.forName("se.krka.kahlua.vm.JavaFunction");
                                        Class<?> callFrameClass = Class.forName("se.krka.kahlua.vm.LuaCallFrame");
                                        Method pushObj = callFrameClass.getMethod("push", Object.class);
                                        Method getArg = callFrameClass.getMethod("get", int.class);

                                        // isEnginePresent / isActive
                                        Object isPresentFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    pushObj.invoke(mArgs[0], Boolean.TRUE);
                                                    return 1;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "isEnginePresent", isPresentFunc);
                                        tableRawset.invoke(pzoTable, "isActive", isPresentFunc);

                                        // isWindowActive
                                        Object isWindowActiveFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    pushObj.invoke(mArgs[0], Boolean.valueOf(isWindowActive()));
                                                    return 1;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "isWindowActive", isWindowActiveFunc);

                                        // getOptimizedRAM
                                        Object getRamFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    pushObj.invoke(mArgs[0], Double.valueOf(cachedRamGb));
                                                    return 1;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "getOptimizedRAM", getRamFunc);

                                        // getVersion
                                        Object getVersionFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    pushObj.invoke(mArgs[0], UpdateChecker.CURRENT_VERSION);
                                                    return 1;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "getVersion", getVersionFunc);

                                        // getTelemetryText
                                        Object getTelemetryFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    pushObj.invoke(mArgs[0], PZOTelemetryHUD.getFormattedHUDText());
                                                    return 1;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "getTelemetryText", getTelemetryFunc);

                                        // openBrowser
                                        Object openBrowserFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    Object callFrame = mArgs[0];
                                                    Object urlArg = getArg.invoke(callFrame, 0);
                                                    if (urlArg != null) {
                                                        openBrowser(urlArg.toString());
                                                    }
                                                    return 0;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "openBrowser", openBrowserFunc);

                                        // purgeRAM
                                        Object purgeRamFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    purgeRAM();
                                                    return 0;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "purgeRAM", purgeRamFunc);

                                        // getHeapUsedMB
                                        Object getHeapUsedFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    long usedMB = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
                                                    pushObj.invoke(mArgs[0], Double.valueOf(usedMB));
                                                    return 1;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "getHeapUsedMB", getHeapUsedFunc);

                                        // getGlCallsFiltered
                                        Object getGlFilteredFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    long totalSkipped = GLStateOptimizer.getGlCallsFiltered() + GLStateOptimizer.getUniformsSkipped();
                                                    pushObj.invoke(mArgs[0], Double.valueOf(totalSkipped));
                                                    return 1;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "getGlCallsFiltered", getGlFilteredFunc);

                                        // openLogsFolder
                                        Object openLogsFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    openLogsFolder();
                                                    return 0;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "openLogsFolder", openLogsFunc);

                                        // copyDiagnosticsToClipboard
                                        Object copyDiagFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    copyDiagnosticsToClipboard();
                                                    return 0;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "copyDiagnosticsToClipboard", copyDiagFunc);

                                        // getDiagnosticsReport
                                        Object getDiagReportFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    pushObj.invoke(mArgs[0], getDiagnosticsReport());
                                                    return 1;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "getDiagnosticsReport", getDiagReportFunc);

                                        // isBetaOptIn
                                        Object isBetaFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    pushObj.invoke(mArgs[0], Boolean.valueOf(PZOConfig.isBetaOptIn()));
                                                    return 1;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "isBetaOptIn", isBetaFunc);

                                        // getChannel
                                        Object getChannelFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    pushObj.invoke(mArgs[0], getChannel());
                                                    return 1;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "getChannel", getChannelFunc);

                                        // getRenderTelemetry (Unstable channel live metrics)
                                        Object getRenderTelemetryFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    pushObj.invoke(mArgs[0], EnhancedRenderTelemetry.toJson());
                                                    return 1;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "getRenderTelemetry", getRenderTelemetryFunc);

                                        // isAVX2SpatialActive
                                        Object isAVX2SpatialFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    pushObj.invoke(mArgs[0], Boolean.valueOf(isAVX2SpatialActive()));
                                                    return 1;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "isAVX2SpatialActive", isAVX2SpatialFunc);

                                        // getHordeZombiesTracked
                                        Object getHordeTrackedFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    pushObj.invoke(mArgs[0], Double.valueOf(getHordeZombiesTracked()));
                                                    return 1;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "getHordeZombiesTracked", getHordeTrackedFunc);

                                        // getHordeCulledOffscreen
                                        Object getHordeCulledFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    pushObj.invoke(mArgs[0], Double.valueOf(getHordeCulledOffscreen()));
                                                    return 1;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "getHordeCulledOffscreen", getHordeCulledFunc);

                                        // getBoneTransformsSaved
                                        Object getBonesSavedFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    pushObj.invoke(mArgs[0], Double.valueOf(getBoneTransformsSaved()));
                                                    return 1;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "getBoneTransformsSaved", getBonesSavedFunc);

                                        // setBetaOptIn
                                        Object setBetaFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    Object callFrame = mArgs[0];
                                                    Object boolArg = getArg.invoke(callFrame, 0);
                                                    if (boolArg instanceof Boolean) {
                                                        PZOConfig.setBetaOptIn((Boolean) boolArg);
                                                    } else if (boolArg instanceof Number) {
                                                        PZOConfig.setBetaOptIn(((Number) boolArg).intValue() != 0);
                                                    }
                                                    return 0;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "setBetaOptIn", setBetaFunc);

                                        // checkForUpdates
                                        Object checkUpdateFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    Thread checkThread = new Thread(() -> {
                                                        try {
                                                            UpdateChecker.UpdateResult res = UpdateChecker.checkForUpdatesSync(4000);
                                                            if (res != null && res.hasUpdate) {
                                                                UpdateDialog.promptIfUpdateAvailable(res.latestVersion, res.downloadUrl, res.dllDownloadUrl);
                                                            }
                                                        } catch (Throwable ignored) {}
                                                    }, "PZO-AsyncUpdatePrompt");
                                                    checkThread.setDaemon(true);
                                                    checkThread.start();
                                                    return 0;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "checkForUpdates", checkUpdateFunc);

                                        // setJvmOption
                                        Object setJvmOptionFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    Object callFrame = mArgs[0];
                                                    Object keyArg = getArg.invoke(callFrame, 0);
                                                    Object valArg = getArg.invoke(callFrame, 1);
                                                    if (keyArg != null && valArg != null) {
                                                        boolean bVal = Boolean.TRUE.equals(valArg) || (valArg instanceof Number && ((Number)valArg).intValue() != 0);
                                                        setJvmOption(keyArg.toString(), bVal);
                                                    }
                                                    return 0;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "setJvmOption", setJvmOptionFunc);

                                        // setJvmIntOption
                                        Object setJvmIntFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    Object callFrame = mArgs[0];
                                                    Object keyArg = getArg.invoke(callFrame, 0);
                                                    Object valArg = getArg.invoke(callFrame, 1);
                                                    if (keyArg != null && valArg instanceof Number) {
                                                        setJvmIntOption(keyArg.toString(), ((Number)valArg).intValue());
                                                    }
                                                    return 0;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "setJvmIntOption", setJvmIntFunc);

                                        // setEngineTuning
                                        Object setEngineTuningFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    Object callFrame = mArgs[0];
                                                    Object keyArg = getArg.invoke(callFrame, 0);
                                                    Object valArg = getArg.invoke(callFrame, 1);
                                                    if (keyArg != null && valArg instanceof Number) {
                                                        EngineTuning.set(keyArg.toString(), ((Number)valArg).doubleValue());
                                                    }
                                                    return 0;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "setEngineTuning", setEngineTuningFunc);

                                        // isMultithreadingNoticeAcknowledged
                                        Object isNoticeAckFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    pushObj.invoke(mArgs[0], Boolean.valueOf(PZOConfig.isMultithreadingNoticeAcknowledged()));
                                                    return 1;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "isMultithreadingNoticeAcknowledged", isNoticeAckFunc);

                                        // isWorldUpscalerActive
                                        Object isWorldUpscalerActiveFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    pushObj.invoke(mArgs[0], Boolean.valueOf(WorldUpscalerGovernor.isActive()));
                                                    return 1;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "isWorldUpscalerActive", isWorldUpscalerActiveFunc);

                                        // getWorldUpscalerRenderScale
                                        Object getUpscalerScaleFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    pushObj.invoke(mArgs[0], Double.valueOf(WorldUpscalerGovernor.getRenderScale()));
                                                    return 1;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "getWorldUpscalerRenderScale", getUpscalerScaleFunc);

                                        // getWorldUpscalerSharpness
                                        Object getUpscalerSharpFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    pushObj.invoke(mArgs[0], Double.valueOf(WorldUpscalerGovernor.getSharpness()));
                                                    return 1;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "getWorldUpscalerSharpness", getUpscalerSharpFunc);

                                        // isViewpointStanddownActive
                                        Object isStanddownActiveFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    pushObj.invoke(mArgs[0], Boolean.valueOf(ViewpointStanddownGovernor.isStanddownActive()));
                                                    return 1;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "isViewpointStanddownActive", isStanddownActiveFunc);

                                        // isViewpointDetected
                                        Object isViewpointDetectedFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    pushObj.invoke(mArgs[0], Boolean.valueOf(ViewpointStanddownGovernor.isViewpointDetected()));
                                                    return 1;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "isViewpointDetected", isViewpointDetectedFunc);

                                        // getViewpointStanddownMode
                                        Object getViewpointModeFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    pushObj.invoke(mArgs[0], ViewpointStanddownGovernor.getStanddownModeString());
                                                    return 1;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "getViewpointStanddownMode", getViewpointModeFunc);

                                        // setStanddownMode / setViewpointStanddownMode
                                        Object setStanddownModeFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    try {
                                                        Object arg = getArg.invoke(mArgs[0], 0);
                                                        if (arg != null) {
                                                            ViewpointStanddownGovernor.setStanddownMode(arg.toString());
                                                        }
                                                    } catch (Throwable ignored) {}
                                                    return 0;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "setStanddownMode", setStanddownModeFunc);
                                        tableRawset.invoke(pzoTable, "setViewpointStanddownMode", setStanddownModeFunc);

                                        // toggleViewpointStanddown
                                        Object toggleStanddownFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    ViewpointStanddownGovernor.toggleStanddown();
                                                    return 0;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "toggleViewpointStanddown", toggleStanddownFunc);

                                        // acknowledgeMultithreadingNotice
                                        Object ackNoticeFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    PZOConfig.setMultithreadingNoticeAcknowledged(true);
                                                    return 0;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "acknowledgeMultithreadingNotice", ackNoticeFunc);

                                        // openWorkshopPage
                                        Object openWorkshopFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    PZOEntrypoint.openBrowser("https://steamcommunity.com/sharedfiles/filedetails/?id=3787481250");
                                                    return 0;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "openWorkshopPage", openWorkshopFunc);

                                        // openGithubPage
                                        Object openGithubFunc = Proxy.newProxyInstance(
                                            javaFuncClass.getClassLoader(),
                                            new Class<?>[]{javaFuncClass},
                                            (proxy, m, mArgs) -> {
                                                if ("call".equals(m.getName())) {
                                                    PZOEntrypoint.openBrowser("https://github.com/prop11/PZO-Launcher/issues");
                                                    return 0;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "openGithubPage", openGithubFunc);

                                    } catch (Throwable t) {
                                        PZOLogger.warn("[PZO Kahlua Bridge] JavaFunction proxy warning: " + t.getMessage());
                                    }

                                    rawset.invoke(env, "PZOEngine", pzoTable);
                                    rawset.invoke(env, "PZOEngineBridge", pzoTable);

                                    // Inject Main Menu Beta Opt-In Tickbox UI into Kahlua
                                    try {
                                        String luaCode =
                                            "local function addPZOBetaToggle()\n" +
                                            "    if not MainScreen or not MainScreen.instance then return end\n" +
                                            "    if MainScreen.instance.inGame then return end\n" +
                                            "    if MainScreen.instance.pzoBetaButton then return end\n" +
                                            "    local isOptedIn = false\n" +
                                            "    if PZOEngine and PZOEngine.isBetaOptIn then\n" +
                                            "        isOptedIn = PZOEngine.isBetaOptIn()\n" +
                                            "    end\n" +
                                            "    local titleText = isOptedIn and 'PZO Beta Channel: [ON]' or 'PZO Beta Channel: [OFF]'\n" +
                                            "    local btnW = 220\n" +
                                            "    local btnH = 26\n" +
                                            "    local btnX = 25\n" +
                                            "    local btnY = (MainScreen.instance.height or getCore():getScreenHeight()) - btnH - 18\n" +
                                            "    local btn = ISButton:new(btnX, btnY, btnW, btnH, titleText, MainScreen.instance, function(target, button)\n" +
                                            "        local curState = false\n" +
                                            "        if PZOEngine and PZOEngine.isBetaOptIn then\n" +
                                            "            curState = PZOEngine.isBetaOptIn()\n" +
                                            "        end\n" +
                                            "        local newState = not curState\n" +
                                            "        if PZOEngine and PZOEngine.setBetaOptIn then\n" +
                                            "            PZOEngine.setBetaOptIn(newState)\n" +
                                            "        end\n" +
                                            "        button.title = newState and 'PZO Beta Channel: [ON]' or 'PZO Beta Channel: [OFF]'\n" +
                                            "        button.borderColor = newState and {r=0.2, g=0.9, b=0.4, a=1.0} or {r=0.5, g=0.5, b=0.5, a=0.8}\n" +
                                            "        button.textColor = newState and {r=0.3, g=1.0, b=0.5, a=1.0} or {r=0.8, g=0.8, b=0.8, a=0.9}\n" +
                                            "        if newState and PZOEngine and PZOEngine.checkForUpdates then\n" +
                                            "            PZOEngine.checkForUpdates()\n" +
                                            "        end\n" +
                                            "    end)\n" +
                                            "    btn:initialise()\n" +
                                            "    btn:instantiate()\n" +
                                            "    btn.backgroundColor = {r=0.08, g=0.10, b=0.15, a=0.90}\n" +
                                            "    btn.borderColor = isOptedIn and {r=0.2, g=0.9, b=0.4, a=1.0} or {r=0.5, g=0.5, b=0.5, a=0.8}\n" +
                                            "    btn.textColor = isOptedIn and {r=0.3, g=1.0, b=0.5, a=1.0} or {r=0.8, g=0.8, b=0.8, a=0.9}\n" +
                                            "    btn:setAnchorLeft(true)\n" +
                                            "    btn:setAnchorRight(false)\n" +
                                            "    btn:setAnchorTop(false)\n" +
                                            "    btn:setAnchorBottom(true)\n" +
                                            "    btn:setVisible(false)\n" +
                                            "    MainScreen.instance:addChild(btn)\n" +
                                            "    MainScreen.instance.pzoBetaButton = btn\n" +
                                            "end\n" +
                                            "local function showPZOMultithreadingNotice()\n" +
                                            "    if not MainScreen or not MainScreen.instance or MainScreen.instance.inGame then return end\n" +
                                            "    if MainScreen.instance.pzoNoticeShown then return end\n" +
                                            "    if not ISModalRichText then return end\n" +
                                            "    if not PZOEngine or not PZOEngine.isMultithreadingNoticeAcknowledged then return end\n" +
                                            "    if PZOEngine.isMultithreadingNoticeAcknowledged() then return end\n" +
                                            "    MainScreen.instance.pzoNoticeShown = true\n" +
                                            "    local scrW = getCore():getScreenWidth()\n" +
                                            "    local scrH = getCore():getScreenHeight()\n" +
                                            "    local modalW = math.min(760, scrW - 40)\n" +
                                            "    local modalH = math.min(540, scrH - 60)\n" +
                                            "    local modalX = (scrW - modalW) / 2\n" +
                                            "    local modalY = (scrH - modalH) / 2\n" +
                                            "    local ver = (PZOEngine and PZOEngine.getVersion and PZOEngine.getVersion()) or \"1.0.3-unstable\"\n" +
                                            "    local fsrActive = (PZOEngine and PZOEngine.isWorldUpscalerActive and PZOEngine.isWorldUpscalerActive()) or false\n" +
                                            "    local fsrStatus = fsrActive and \" <RGB:0.3,1.0,0.5>[ENABLED] <RGB:0.85,0.85,0.85>\" or \" <RGB:0.7,0.7,0.7>[OFF - Launch Option Available] <RGB:0.85,0.85,0.85>\"\n" +
                                            "    local text = \" <CENTRE> <SIZE:medium> <RGB:0.25,0.95,0.45> Project Zomboid Optimiser (PZO v\" .. ver .. \") <LINE> \" ..\n" +
                                            "        \"<SIZE:large> <RGB:1,1,1> Multi-Threading Optimizations Active! <LINE> <LINE> \" ..\n" +
                                            "        \"<LEFT> <SIZE:small> <RGB:0.9,0.9,0.9> \" ..\n" +
                                            "        \"PZO's parallel multi-threaded engine is active. Multi-core chunk streaming, background island simulation, and native kernel acceleration are distributing workload across your CPU cores to maximize framerates and eliminate stutters. <LINE> <LINE> \" ..\n" +
                                            "        \"<RGB:0.2,0.85,1.0> AMD FSR 1.0 Super Resolution:\" .. fsrStatus .. \" <LINE> \" ..\n" +
                                            "        \"<RGB:0.85,0.85,0.85> To boost framerates on 1440p/4K displays or handhelds (Steam Deck, ROG Ally), launch Project Zomboid with the launch parameter: <RGB:0.3,1.0,0.5> -pzo_fsr <RGB:0.85,0.85,0.85> (set via Steam -> Properties -> Launch Options). This renders the 3D world at 75% scale and upscales it via edge-adaptive FSR while keeping UI and text at full native clarity! <LINE> <LINE> \" ..\n" +
                                            "        \"<RGB:1.0,0.85,0.3> Issues or Feedback: <LINE> \" ..\n" +
                                            "        \"<RGB:0.85,0.85,0.85> If you experience any issues, crashes, or compatibility glitches, please report them on our <RGB:0.4,0.8,1.0> GitHub <RGB:0.85,0.85,0.85> or the <RGB:0.4,0.8,1.0> Steam Workshop <RGB:0.85,0.85,0.85> page so we can resolve them quickly. <LINE> <LINE> \" ..\n" +
                                            "        \"<RGB:0.3,1.0,0.5> Enjoying the Performance? <LINE> \" ..\n" +
                                            "        \"<RGB:0.85,0.85,0.85> Likewise, if this mod is helping your game run smoother, please take a moment to <RGB:1.0,0.85,0.2> give us an upvote <RGB:0.85,0.85,0.85> on the Steam Workshop! It helps more survivors discover PZO and supports future development. <LINE> \"\n" +
                                            "    local modal = ISModalRichText:new(modalX, modalY, modalW, modalH, text, false, nil, function(target, button)\n" +
                                            "        if PZOEngine and PZOEngine.acknowledgeMultithreadingNotice then\n" +
                                            "            PZOEngine.acknowledgeMultithreadingNotice()\n" +
                                            "        end\n" +
                                            "        if MainScreen and MainScreen.instance then\n" +
                                            "            MainScreen.instance.pzoNoticeModal = nil\n" +
                                            "        end\n" +
                                            "    end)\n" +
                                            "    modal:initialise()\n" +
                                            "    modal.destroyOnClick = true\n" +
                                            "    modal.backgroundColor = {r=0.07, g=0.08, b=0.11, a=0.96}\n" +
                                            "    modal.borderColor = {r=0.25, g=0.85, b=0.45, a=1.0}\n" +
                                            "    local old_destroy = modal.destroy\n" +
                                            "    modal.destroy = function(self)\n" +
                                            "        if PZOEngine and PZOEngine.acknowledgeMultithreadingNotice then\n" +
                                            "            PZOEngine.acknowledgeMultithreadingNotice()\n" +
                                            "        end\n" +
                                            "        if MainScreen and MainScreen.instance then\n" +
                                            "            MainScreen.instance.pzoNoticeModal = nil\n" +
                                            "        end\n" +
                                            "        old_destroy(self)\n" +
                                            "    end\n" +
                                            "    local tm = getTextManager and getTextManager()\n" +
                                            "    local font = UIFont.Small\n" +
                                            "    local fontH = (tm and tm:getFontHeight(font)) or 16\n" +
                                            "    local btnH = math.max(34, fontH + 14)\n" +
                                            "    local reservedBottom = btnH + 32\n" +
                                            "    local wsW = math.max(180, (tm and tm:MeasureStringX(font, \"Steam Workshop\") or 140) + 40)\n" +
                                            "    local ghW = math.max(170, (tm and tm:MeasureStringX(font, \"GitHub Issues\") or 130) + 40)\n" +
                                            "    local okW = math.max(170, (tm and tm:MeasureStringX(font, \"Acknowledge\") or 130) + 40)\n" +
                                            "    if modal.chatText then\n" +
                                            "        modal.chatText:setHeight(modal:getHeight() - reservedBottom)\n" +
                                            "        modal.chatText:updateScrollbars()\n" +
                                            "        modal.chatText.onMouseWheel = function(self, del)\n" +
                                            "            self:setYScroll(self:getYScroll() - (del * 30))\n" +
                                            "            return true\n" +
                                            "        end\n" +
                                            "    end\n" +
                                            "    modal.onMouseWheel = function(self, del)\n" +
                                            "        if self.chatText and self.chatText.onMouseWheel then\n" +
                                            "            return self.chatText:onMouseWheel(del)\n" +
                                            "        end\n" +
                                            "        return false\n" +
                                            "    end\n" +
                                            "    local wsBtn = ISButton:new(24, modal:getHeight() - btnH - 16, wsW, btnH, \"Steam Workshop\", modal, function(self, button)\n" +
                                            "        if openUrl then\n" +
                                            "            openUrl(\"https://steamcommunity.com/sharedfiles/filedetails/?id=3787481250\")\n" +
                                            "        elseif PZOEngine and PZOEngine.openWorkshopPage then\n" +
                                            "            PZOEngine.openWorkshopPage()\n" +
                                            "        end\n" +
                                            "    end)\n" +
                                            "    wsBtn:initialise()\n" +
                                            "    wsBtn:instantiate()\n" +
                                            "    wsBtn:setFont(font)\n" +
                                            "    wsBtn.font = font\n" +
                                            "    wsBtn.anchorTop = false\n" +
                                            "    wsBtn.anchorBottom = true\n" +
                                            "    wsBtn.backgroundColor = {r=0.12, g=0.35, b=0.55, a=1.0}\n" +
                                            "    wsBtn.borderColor = {r=0.3, g=0.6, b=0.9, a=1.0}\n" +
                                            "    wsBtn.textColor = {r=0.9, g=0.95, b=1.0, a=1.0}\n" +
                                            "    modal:addChild(wsBtn)\n" +
                                            "    modal.pzoWsBtn = wsBtn\n" +
                                            "    local ghBtn = ISButton:new(24 + wsW + 16, modal:getHeight() - btnH - 16, ghW, btnH, \"GitHub Issues\", modal, function(self, button)\n" +
                                            "        if openUrl then\n" +
                                            "            openUrl(\"https://github.com/prop11/PZO-Launcher/issues\")\n" +
                                            "        elseif PZOEngine and PZOEngine.openGithubPage then\n" +
                                            "            PZOEngine.openGithubPage()\n" +
                                            "        end\n" +
                                            "    end)\n" +
                                            "    ghBtn:initialise()\n" +
                                            "    ghBtn:instantiate()\n" +
                                            "    ghBtn:setFont(font)\n" +
                                            "    ghBtn.font = font\n" +
                                            "    ghBtn.anchorTop = false\n" +
                                            "    ghBtn.anchorBottom = true\n" +
                                            "    ghBtn.backgroundColor = {r=0.2, g=0.2, b=0.25, a=1.0}\n" +
                                            "    ghBtn.borderColor = {r=0.5, g=0.5, b=0.6, a=1.0}\n" +
                                            "    ghBtn.textColor = {r=0.9, g=0.9, b=0.9, a=1.0}\n" +
                                            "    modal:addChild(ghBtn)\n" +
                                            "    modal.pzoGhBtn = ghBtn\n" +
                                            "    if modal.ok then\n" +
                                            "        modal.ok:setTitle(\"Acknowledge\")\n" +
                                            "        modal.ok.title = \"Acknowledge\"\n" +
                                            "        modal.ok:setFont(font)\n" +
                                            "        modal.ok.font = font\n" +
                                            "        modal.ok.anchorTop = false\n" +
                                            "        modal.ok.anchorBottom = true\n" +
                                            "        modal.ok.backgroundColor = {r=0.15, g=0.55, b=0.25, a=1.0}\n" +
                                            "        modal.ok.borderColor = {r=0.3, g=0.8, b=0.4, a=1.0}\n" +
                                            "        modal.ok.textColor = {r=1.0, g=1.0, b=1.0, a=1.0}\n" +
                                            "    end\n" +
                                            "    local function layoutModalButtons(self)\n" +
                                            "        if not self then return end\n" +
                                            "        local tmInst = getTextManager and getTextManager()\n" +
                                            "        local cFont = UIFont.Small\n" +
                                            "        local cFontH = (tmInst and tmInst:getFontHeight(cFont)) or 16\n" +
                                            "        local bH = math.max(34, cFontH + 14)\n" +
                                            "        local bY = self:getHeight() - bH - 16\n" +
                                            "        local cWsW = math.max(180, (tmInst and tmInst:MeasureStringX(cFont, \"Steam Workshop\") or 140) + 40)\n" +
                                            "        local cGhW = math.max(170, (tmInst and tmInst:MeasureStringX(cFont, \"GitHub Issues\") or 130) + 40)\n" +
                                            "        local cOkW = math.max(170, (tmInst and tmInst:MeasureStringX(cFont, \"Acknowledge\") or 130) + 40)\n" +
                                            "        local padX = 24\n" +
                                            "        local gap = 16\n" +
                                            "        local avail = self:getWidth() - (padX * 2)\n" +
                                            "        if avail < (cWsW + cGhW + cOkW + gap * 2) then\n" +
                                            "            gap = 8\n" +
                                            "            padX = 12\n" +
                                            "        end\n" +
                                            "        if self.pzoWsBtn then\n" +
                                            "            self.pzoWsBtn:setX(padX)\n" +
                                            "            self.pzoWsBtn:setY(bY)\n" +
                                            "            self.pzoWsBtn:setWidth(cWsW)\n" +
                                            "            self.pzoWsBtn:setHeight(bH)\n" +
                                            "        end\n" +
                                            "        if self.pzoGhBtn then\n" +
                                            "            self.pzoGhBtn:setX(padX + cWsW + gap)\n" +
                                            "            self.pzoGhBtn:setY(bY)\n" +
                                            "            self.pzoGhBtn:setWidth(cGhW)\n" +
                                            "            self.pzoGhBtn:setHeight(bH)\n" +
                                            "        end\n" +
                                            "        if self.ok then\n" +
                                            "            self.ok:setX(self:getWidth() - cOkW - padX)\n" +
                                            "            self.ok:setY(bY)\n" +
                                            "            self.ok:setWidth(cOkW)\n" +
                                            "            self.ok:setHeight(bH)\n" +
                                            "        end\n" +
                                            "    end\n" +
                                            "    modal.updateButtons = function(self)\n" +
                                            "        layoutModalButtons(self)\n" +
                                            "    end\n" +
                                            "    modal.update = function(self)\n" +
                                            "        ISPanelJoypad.update(self)\n" +
                                            "        local tmInst = getTextManager and getTextManager()\n" +
                                            "        local cFontH = (tmInst and tmInst:getFontHeight(UIFont.Small)) or 16\n" +
                                            "        local bH = math.max(34, cFontH + 14)\n" +
                                            "        local rBottom = bH + 32\n" +
                                            "        local maxModalH = getCore():getScreenHeight() - 40\n" +
                                            "        if self:getHeight() > maxModalH then\n" +
                                            "            self:setHeight(maxModalH)\n" +
                                            "            self:ignoreHeightChange()\n" +
                                            "            self:setY(20)\n" +
                                            "        end\n" +
                                            "        if self.chatText then\n" +
                                            "            local targetChatH = math.max(60, self:getHeight() - rBottom)\n" +
                                            "            if self.chatText:getHeight() ~= targetChatH then\n" +
                                            "                self.chatText:setHeight(targetChatH)\n" +
                                            "            end\n" +
                                            "            self.chatText:updateScrollbars()\n" +
                                            "        end\n" +
                                            "        layoutModalButtons(self)\n" +
                                            "        if self.alwaysOnTop then\n" +
                                            "            self:bringToTop()\n" +
                                            "        end\n" +
                                            "    end\n" +
                                            "    modal.setHeightToContents = function(self)\n" +
                                            "        local tmInst = getTextManager and getTextManager()\n" +
                                            "        local cFontH = (tmInst and tmInst:getFontHeight(UIFont.Small)) or 16\n" +
                                            "        local bH = math.max(34, cFontH + 14)\n" +
                                            "        local rBottom = bH + 32\n" +
                                            "        local minH = (self.chatText and self.chatText:getScrollHeight() or 200) + rBottom + 10\n" +
                                            "        local maxH = getCore():getScreenHeight() - 40\n" +
                                            "        self:setHeight(math.min(minH, maxH))\n" +
                                            "        self:ignoreHeightChange()\n" +
                                            "        if self.updateButtons then self:updateButtons() end\n" +
                                            "    end\n" +
                                            "    layoutModalButtons(modal)\n" +
                                            "    modal:addToUIManager()\n" +
                                            "    modal:setAlwaysOnTop(true)\n" +
                                            "    modal:bringToTop()\n" +
                                            "    if MainScreen and MainScreen.instance then\n" +
                                            "        MainScreen.instance.pzoNoticeModal = modal\n" +
                                            "    end\n" +
                                            "    local joypadData = JoypadState and JoypadState.getMainMenuJoypad and JoypadState.getMainMenuJoypad()\n" +
                                            "    if joypadData then\n" +
                                            "        joypadData.focus = modal\n" +
                                            "        if updateJoypadFocus then updateJoypadFocus(joypadData) end\n" +
                                            "    end\n" +
                                            "end\n" +
                                            "local function addPZOSettingsTab()\n" +
                                            "    if not MainOptions then return end\n" +
                                            "    local function injectTab(mo)\n" +
                                            "        if not mo or not mo.tabs then return end\n" +
                                            "        if mo.pzoTabAdded then return end\n" +
                                            "        mo.pzoTabAdded = true\n" +
                                            "        local tabs = mo.tabs\n" +
                                            "        local tabW = tabs.width or (mo.width - 40)\n" +
                                            "        local tabH = (tabs.height and tabs.tabHeight) and (tabs.height - tabs.tabHeight) or (mo.height - 100)\n" +
                                            "        local pzoPanel = ISPanelJoypad:new(0, 0, tabW, tabH)\n" +
                                            "        pzoPanel:initialise()\n" +
                                            "        pzoPanel:instantiate()\n" +
                                            "        pzoPanel:setAnchorRight(true)\n" +
                                            "        pzoPanel:setAnchorBottom(true)\n" +
                                            "        pzoPanel:setScrollChildren(true)\n" +
                                            "        pzoPanel:addScrollBars()\n" +
                                            "        pzoPanel.backgroundColor = {r=0, g=0, b=0, a=0}\n" +
                                            "        pzoPanel.borderColor = {r=0, g=0, b=0, a=0}\n" +
                                            "        function pzoPanel:apply() end\n" +
                                            "        function pzoPanel:save() end\n" +
                                            "        function pzoPanel:restoreDefault() end\n" +
                                            "        local function makeButton(title, bg, border, text, onClick)\n" +
                                            "            local b = ISButton:new(0, 0, 100, 24, title, pzoPanel, onClick)\n" +
                                            "            b:initialise()\n" +
                                            "            b:instantiate()\n" +
                                            "            b.backgroundColor = bg\n" +
                                            "            b.borderColor = border\n" +
                                            "            b.textColor = text\n" +
                                            "            pzoPanel:addChild(b)\n" +
                                            "            return b\n" +
                                            "        end\n" +
                                            "        local modeBtn = makeButton('Standdown Mode: [AUTO] (Click to Cycle)',\n" +
                                            "            {r=0.10, g=0.14, b=0.20, a=0.95}, {r=0.3, g=0.6, b=0.9, a=1.0}, {r=0.85, g=0.95, b=1.0, a=1.0},\n" +
                                            "            function(target, button)\n" +
                                            "                if PZOEngine and PZOEngine.toggleViewpointStanddown then PZOEngine.toggleViewpointStanddown() end\n" +
                                            "            end)\n" +
                                            "        local wsBtn = makeButton('Steam Workshop',\n" +
                                            "            {r=0.12, g=0.35, b=0.55, a=1.0}, {r=0.3, g=0.6, b=0.9, a=1.0}, {r=0.9, g=0.95, b=1.0, a=1.0},\n" +
                                            "            function(target, button)\n" +
                                            "                if openUrl then openUrl('https://steamcommunity.com/sharedfiles/filedetails/?id=3787481250')\n" +
                                            "                elseif PZOEngine and PZOEngine.openWorkshopPage then PZOEngine.openWorkshopPage() end\n" +
                                            "            end)\n" +
                                            "        local ghBtn = makeButton('GitHub Issues',\n" +
                                            "            {r=0.20, g=0.22, b=0.26, a=1.0}, {r=0.5, g=0.5, b=0.6, a=1.0}, {r=0.9, g=0.9, b=0.9, a=1.0},\n" +
                                            "            function(target, button)\n" +
                                            "                if openUrl then openUrl('https://github.com/prop11/PZO-Launcher/issues')\n" +
                                            "                elseif PZOEngine and PZOEngine.openGithubPage then PZOEngine.openGithubPage() end\n" +
                                            "            end)\n" +
                                            "        local updateBtn = makeButton('Check for Updates',\n" +
                                            "            {r=0.15, g=0.45, b=0.25, a=1.0}, {r=0.3, g=0.8, b=0.4, a=1.0}, {r=0.9, g=1.0, b=0.9, a=1.0},\n" +
                                            "            function(target, button)\n" +
                                            "                if PZOEngine and PZOEngine.checkForUpdates then PZOEngine.checkForUpdates() end\n" +
                                            "            end)\n" +
                                            "        -- Layout is derived from the live font metrics every frame, so any UI font size fits\n" +
                                            "        local function fitButton(btn, x, y, h)\n" +
                                            "            local tm = getTextManager()\n" +
                                            "            btn:setX(x)\n" +
                                            "            btn:setY(y)\n" +
                                            "            btn:setWidth(tm:MeasureStringX(UIFont.Small, btn.title) + 24)\n" +
                                            "            btn:setHeight(h)\n" +
                                            "            return btn:getWidth()\n" +
                                            "        end\n" +
                                            "        pzoPanel.prerender = function(self)\n" +
                                            "            ISPanelJoypad.prerender(self)\n" +
                                            "            local tm = getTextManager()\n" +
                                            "            local hS = tm:getFontHeight(UIFont.Small)\n" +
                                            "            local hM = tm:getFontHeight(UIFont.Medium)\n" +
                                            "            local pad = math.max(10, math.floor(hS * 0.6))\n" +
                                            "            local gap = math.max(2, math.floor(hS * 0.2))\n" +
                                            "            local btnH = hS + 10\n" +
                                            "            local x0 = 24\n" +
                                            "            local maxW = math.max(320, self:getWidth() - x0 - 32)\n" +
                                            "            local ver = (PZOEngine and PZOEngine.getVersion and PZOEngine.getVersion()) or '1.0.3-unstable'\n" +
                                            "            local standdownActive, viewpointDetected, standdownMode = false, false, 'AUTO'\n" +
                                            "            local fsrActive, fsrScale, fsrSharp = false, 0.75, 0.8\n" +
                                            "            if PZOEngine then\n" +
                                            "                if PZOEngine.isViewpointStanddownActive then standdownActive = PZOEngine.isViewpointStanddownActive() end\n" +
                                            "                if PZOEngine.isViewpointDetected then viewpointDetected = PZOEngine.isViewpointDetected() end\n" +
                                            "                if PZOEngine.getViewpointStanddownMode then standdownMode = PZOEngine.getViewpointStanddownMode() or 'AUTO' end\n" +
                                            "                if PZOEngine.isWorldUpscalerActive then fsrActive = PZOEngine.isWorldUpscalerActive() end\n" +
                                            "                if PZOEngine.getWorldUpscalerRenderScale then fsrScale = PZOEngine.getWorldUpscalerRenderScale() or fsrScale end\n" +
                                            "                if PZOEngine.getWorldUpscalerSharpness then fsrSharp = PZOEngine.getWorldUpscalerSharpness() or fsrSharp end\n" +
                                            "            end\n" +
                                            "            local y = 12\n" +
                                            "            self:drawText('PROJECT ZOMBOID OPTIMISER (PZO v' .. ver .. ')', x0, y, 0.25, 0.95, 0.45, 1.0, UIFont.Medium)\n" +
                                            "            y = y + hM + gap\n" +
                                            "            self:drawText('High-Performance Multi-Core Architecture & Hardware Acceleration Engine', x0, y, 0.7, 0.7, 0.7, 0.9, UIFont.Small)\n" +
                                            "            y = y + hS + pad\n" +
                                            "            -- Draws a bordered card sized to its contents; returns the y below the card and the y below its text\n" +
                                            "            local function card(top, title, tc, lines, extraH, bg, bd)\n" +
                                            "                local w = tm:MeasureStringX(UIFont.Medium, title)\n" +
                                            "                for _, ln in ipairs(lines) do w = math.max(w, tm:MeasureStringX(UIFont.Small, ln[1])) end\n" +
                                            "                w = math.min(maxW, math.max(480, w + pad * 2))\n" +
                                            "                local h = pad + hM + gap + #lines * (hS + gap) + extraH + pad\n" +
                                            "                self:drawRect(x0, top, w, h, bg[1], bg[2], bg[3], bg[4])\n" +
                                            "                self:drawRectBorder(x0, top, w, h, bd[1], bd[2], bd[3], bd[4])\n" +
                                            "                local ty = top + pad\n" +
                                            "                self:drawText(title, x0 + pad, ty, tc[1], tc[2], tc[3], 1.0, UIFont.Medium)\n" +
                                            "                ty = ty + hM + gap\n" +
                                            "                for _, ln in ipairs(lines) do\n" +
                                            "                    self:drawText(ln[1], x0 + pad, ty, ln[2], ln[3], ln[4], ln[5] or 1.0, UIFont.Small)\n" +
                                            "                    ty = ty + hS + gap\n" +
                                            "                end\n" +
                                            "                return top + h, ty\n" +
                                            "            end\n" +
                                            "            local vpLines\n" +
                                            "            local vpTitle, vpBg, vpBd\n" +
                                            "            if standdownActive then\n" +
                                            "                vpTitle, vpBg, vpBd = {0.25, 0.95, 0.45}, {0.88, 0.08, 0.20, 0.12}, {1.0, 0.25, 0.85, 0.45}\n" +
                                            "                vpLines = {\n" +
                                            "                    {'STATUS: [STANDDOWN ACTIVE - 3D COMPATIBILITY SAFEGUARD ENGAGED]', 0.35, 1.0, 0.55},\n" +
                                            "                    {'PZO has disarmed conflicting fog buffers, 2D screen culling, and weather geometry passes.', 0.9, 0.95, 0.9, 0.95},\n" +
                                            "                    {'Project Viewpoint & True Weathers are running with full shader compatibility.', 0.8, 0.9, 0.8, 0.9},\n" +
                                            "                }\n" +
                                            "            else\n" +
                                            "                vpTitle, vpBg, vpBd = {0.35, 0.75, 1.0}, {0.88, 0.10, 0.14, 0.22}, {1.0, 0.35, 0.65, 0.90}\n" +
                                            "                vpLines = {\n" +
                                            "                    {'STATUS: [STANDDOWN INACTIVE - FULL PZO GRAPHICS ACCELERATION RUNNING]', 0.7, 0.85, 1.0},\n" +
                                            "                    {'Full PZO visual optimizations are active: Quarter-Buffer Fog, Screen-Space Frustum Culling, and AVX2 sweeps.', 0.85, 0.85, 0.85, 0.95},\n" +
                                            "                    {'No 3D camera mods detected. PZO is maximizing Build 42 framerates and memory efficiency.', 0.75, 0.75, 0.75, 0.9},\n" +
                                            "                }\n" +
                                            "            end\n" +
                                            "            if viewpointDetected then\n" +
                                            "                table.insert(vpLines, {'Viewpoint / True Weathers Detected: [YES] (Safeguard auto-engaged)', 0.3, 1.0, 0.5})\n" +
                                            "            else\n" +
                                            "                table.insert(vpLines, {'Viewpoint / True Weathers Detected: [NO] (Vanilla camera active)', 0.7, 0.7, 0.7})\n" +
                                            "            end\n" +
                                            "            local vpBottom, vpTextBottom = card(y, 'PROJECT VIEWPOINT & 3D CAMERA MOD COMPATIBILITY', vpTitle, vpLines, btnH + gap, vpBg, vpBd)\n" +
                                            "            modeBtn.title = 'Standdown Mode: [' .. standdownMode .. '] (Click to Cycle)'\n" +
                                            "            fitButton(modeBtn, x0 + pad, vpTextBottom + gap, btnH)\n" +
                                            "            if standdownActive then\n" +
                                            "                modeBtn.borderColor = {r=0.25, g=0.85, b=0.45, a=1.0}\n" +
                                            "                modeBtn.textColor = {r=0.35, g=1.0, b=0.55, a=1.0}\n" +
                                            "            else\n" +
                                            "                modeBtn.borderColor = {r=0.4, g=0.6, b=0.8, a=1.0}\n" +
                                            "                modeBtn.textColor = {r=0.8, g=0.9, b=1.0, a=1.0}\n" +
                                            "            end\n" +
                                            "            y = vpBottom + pad\n" +
                                            "            local fsrLines\n" +
                                            "            if fsrActive then\n" +
                                            "                fsrLines = {\n" +
                                            "                    {'VISUAL NOTIFICATION: FSR 1.0 IS CURRENTLY [ENABLED]', 0.35, 1.0, 0.55},\n" +
                                            "                    {'Render Scale: ' .. math.floor(fsrScale * 100 + 0.5) .. '%  |  RCAS Sharpening: ' .. math.floor(fsrSharp * 100 + 0.5) .. '%  |  UI: Native 100%', 0.9, 0.95, 0.9},\n" +
                                            "                    {'The 3D game world is rendered at a performance scale and upscaled with AMD FSR 1.0 every frame.', 0.85, 0.85, 0.85, 0.95},\n" +
                                            "                    {'Boosts framerates on handhelds and high-resolution displays while keeping UI text crisp.', 0.8, 0.8, 0.8, 0.9},\n" +
                                            "                    {'Status: Active via Steam Launch Option (-pzo_fsr)', 0.2, 0.85, 1.0},\n" +
                                            "                }\n" +
                                            "                y = card(y, 'AMD FIDELITYFX SUPER RESOLUTION (FSR 1.0)', {0.25, 0.95, 0.45}, fsrLines, 0, {0.90, 0.06, 0.20, 0.10}, {1.0, 0.20, 0.90, 0.45})\n" +
                                            "            else\n" +
                                            "                fsrLines = {\n" +
                                            "                    {'VISUAL NOTIFICATION: FSR 1.0 IS CURRENTLY [DISABLED]', 1.0, 0.45, 0.35},\n" +
                                            "                    {'The game world is currently rendering at full native resolution.', 0.85, 0.85, 0.85, 0.95},\n" +
                                            "                    {'How to Enable: In Steam, right-click Project Zomboid -> Properties -> General -> Launch Options.', 0.95, 0.95, 0.95},\n" +
                                            "                    {'Enter:  -pzo_fsr   and launch the game.', 0.35, 1.0, 0.55},\n" +
                                            "                    {'Boosts FPS on Steam Deck, ROG Ally, integrated APUs, and 1440p / 4K displays.', 0.8, 0.8, 0.8, 0.9},\n" +
                                            "                }\n" +
                                            "                y = card(y, 'AMD FIDELITYFX SUPER RESOLUTION (FSR 1.0)', {1.0, 0.75, 0.3}, fsrLines, 0, {0.90, 0.18, 0.12, 0.08}, {1.0, 0.90, 0.55, 0.20})\n" +
                                            "            end\n" +
                                            "            y = y + pad\n" +
                                            "            local bx = x0\n" +
                                            "            for _, b in ipairs({wsBtn, ghBtn, updateBtn}) do\n" +
                                            "                local bw = tm:MeasureStringX(UIFont.Small, b.title) + 24\n" +
                                            "                if bx > x0 and bx + bw > x0 + maxW then\n" +
                                            "                    bx = x0\n" +
                                            "                    y = y + btnH + gap\n" +
                                            "                end\n" +
                                            "                bx = bx + fitButton(b, bx, y, btnH) + 12\n" +
                                            "            end\n" +
                                            "            self:setScrollHeight(y + btnH + pad)\n" +
                                            "        end\n" +
                                            "        tabs:addView('PZO', pzoPanel)\n" +
                                            "    end\n" +
                                            "    if not MainOptions.pzoSettingsHooked then\n" +
                                            "        MainOptions.pzoSettingsHooked = true\n" +
                                            "        local old_mo_create = MainOptions.create\n" +
                                            "        MainOptions.create = function(self)\n" +
                                            "            old_mo_create(self)\n" +
                                            "            injectTab(self)\n" +
                                            "        end\n" +
                                            "    end\n" +
                                            "    if MainOptions.instance and MainOptions.instance.tabs then\n" +
                                            "        injectTab(MainOptions.instance)\n" +
                                            "    end\n" +
                                            "end\n" +
                                            "local function isMainMenuOnly(ms)\n" +
                                            "    if not ms or ms.inGame then return false end\n" +
                                            "    if not ms.bottomPanel or not ms.bottomPanel:getIsVisible() then return false end\n" +
                                            "    if ms.mainOptions and ms.mainOptions:getIsVisible() then return false end\n" +
                                            "    if ms.soloScreen and ms.soloScreen:getIsVisible() then return false end\n" +
                                            "    if ms.loadScreen and ms.loadScreen:getIsVisible() then return false end\n" +
                                            "    if ms.modSelect and ms.modSelect:getIsVisible() then return false end\n" +
                                            "    if ms.multiplayer and ms.multiplayer:getIsVisible() then return false end\n" +
                                            "    if ms.onlineCoopScreen and ms.onlineCoopScreen:getIsVisible() then return false end\n" +
                                            "    if ms.workshopSubmit and ms.workshopSubmit:getIsVisible() then return false end\n" +
                                            "    if ms.creditsScreen and ms.creditsScreen:getIsVisible() then return false end\n" +
                                            "    if ms.charCreationMain and ms.charCreationMain:getIsVisible() then return false end\n" +
                                            "    return true\n" +
                                            "end\n" +
                                            "local function pzoOnMainMenuEnter()\n" +
                                            "    addPZOBetaToggle()\n" +
                                            "    showPZOMultithreadingNotice()\n" +
                                            "    addPZOSettingsTab()\n" +
                                            "    if MainScreen and not MainScreen.pzoHooked then\n" +
                                            "        MainScreen.pzoHooked = true\n" +
                                            "        local old_prerender = MainScreen.prerender\n" +
                                            "        MainScreen.prerender = function(self)\n" +
                                            "            old_prerender(self)\n" +
                                            "            if not self.inGame and not self.pzoBetaButton then\n" +
                                            "                addPZOBetaToggle()\n" +
                                            "            end\n" +
                                            "            if not self.inGame and not self.pzoNoticeChecked then\n" +
                                            "                self.pzoNoticeChecked = true\n" +
                                            "                showPZOMultithreadingNotice()\n" +
                                            "            end\n" +
                                            "            if MainOptions and MainOptions.instance and MainOptions.instance.tabs and not MainOptions.instance.pzoTabAdded then\n" +
                                            "                addPZOSettingsTab()\n" +
                                            "            end\n" +
                                            "            if self.pzoBetaButton then\n" +
                                            "                local shouldShow = isMainMenuOnly(self)\n" +
                                            "                if self.pzoBetaButton:getIsVisible() ~= shouldShow then\n" +
                                            "                    self.pzoBetaButton:setVisible(shouldShow)\n" +
                                            "                end\n" +
                                            "            end\n" +
                                            "        end\n" +
                                            "        if MainScreen.setBottomPanelVisible then\n" +
                                            "            local old_setBottom = MainScreen.setBottomPanelVisible\n" +
                                            "            MainScreen.setBottomPanelVisible = function(panelSelf, visible)\n" +
                                            "                old_setBottom(panelSelf, visible)\n" +
                                            "                if panelSelf.parent and panelSelf.parent.pzoBetaButton then\n" +
                                            "                    panelSelf.parent.pzoBetaButton:setVisible(visible and isMainMenuOnly(panelSelf.parent))\n" +
                                            "                end\n" +
                                            "            end\n" +
                                            "        end\n" +
                                            "    end\n" +
                                            "end\n" +
                                            "if Events and Events.OnMainMenuEnter then Events.OnMainMenuEnter.Add(pzoOnMainMenuEnter) end\n" +
                                            "local function pzoOnResolutionChange()\n" +
                                            "    if MainScreen and MainScreen.instance and MainScreen.instance.pzoBetaButton then\n" +
                                            "        local btn = MainScreen.instance.pzoBetaButton\n" +
                                            "        btn:setY((MainScreen.instance.height or getCore():getScreenHeight()) - btn.height - 18)\n" +
                                            "    end\n" +
                                            "    if MainScreen and MainScreen.instance and MainScreen.instance.pzoNoticeModal then\n" +
                                            "        local modal = MainScreen.instance.pzoNoticeModal\n" +
                                            "        local scrW = getCore():getScreenWidth()\n" +
                                            "        local scrH = getCore():getScreenHeight()\n" +
                                            "        modal:setX((scrW - modal:getWidth()) / 2)\n" +
                                            "        modal:setY((scrH - modal:getHeight()) / 2)\n" +
                                            "    end\n" +
                                            "    if MainOptions and MainOptions.instance and MainOptions.instance.tabs and not MainOptions.instance.pzoTabAdded then\n" +
                                            "        addPZOSettingsTab()\n" +
                                            "    end\n" +
                                            "end\n" +
                                            "if Events and Events.OnResolutionChange then Events.OnResolutionChange.Add(pzoOnResolutionChange) end\n" +
                                            "addPZOSettingsTab()\n" +
                                            "if getFileWriter and not _G.pzoFileWriterHooked then\n" +
                                            "    _G.pzoFileWriterHooked = true\n" +
                                            "    local orig_gfw = getFileWriter\n" +
                                            "    getFileWriter = function(filename, createIfNull, append)\n" +
                                            "        if PZOEngineBridge and PZOEngineBridge.shouldThrottleDiskWrite and PZOEngineBridge.shouldThrottleDiskWrite(filename, append) then\n" +
                                            "            local dummy = PZOEngineBridge.getDummyFileWriter()\n" +
                                            "            if dummy then return dummy end\n" +
                                            "        end\n" +
                                            "        return orig_gfw(filename, createIfNull, append)\n" +
                                            "    end\n" +
                                            "end\n" +
                                            "if getModFileWriter and not _G.pzoModFileWriterHooked then\n" +
                                            "    _G.pzoModFileWriterHooked = true\n" +
                                            "    local orig_gmfw = getModFileWriter\n" +
                                            "    getModFileWriter = function(modId, filename, createIfNull, append)\n" +
                                            "        local key = tostring(modId) .. '/' .. tostring(filename)\n" +
                                            "        if PZOEngineBridge and PZOEngineBridge.shouldThrottleDiskWrite and PZOEngineBridge.shouldThrottleDiskWrite(key, append) then\n" +
                                            "            local dummy = PZOEngineBridge.getDummyFileWriter()\n" +
                                            "            if dummy then return dummy end\n" +
                                            "        end\n" +
                                            "        return orig_gmfw(modId, filename, createIfNull, append)\n" +
                                            "    end\n" +
                                            "end\n";

                                        Class<?> compilerClass = Class.forName("se.krka.kahlua.luaj.compiler.LuaCompiler");
                                        Method loadstringMethod = compilerClass.getMethod("loadstring", String.class, String.class, Class.forName("se.krka.kahlua.vm.KahluaTable"));
                                        Object closure = loadstringMethod.invoke(null, luaCode, "PZOBetaUI", env);
                                        if (closure != null) {
                                            try {
                                                Field protoField = closure.getClass().getField("prototype");
                                                Object rootProto = protoField.get(closure);
                                                sanitizePrototype(rootProto, "media/lua/client/OptionScreens/MainScreen.lua");
                                            } catch (Throwable ignored) {}

                                            Field callerField = lmClass.getField("caller");
                                            Object caller = callerField.get(null);
                                            Field threadField = lmClass.getField("thread");
                                            Object thread = threadField.get(null);
                                            if (caller != null && thread != null) {
                                                Field debugOwnerField = null;
                                                Thread prevOwner = null;
                                                try {
                                                    debugOwnerField = thread.getClass().getField("debugOwnerThread");
                                                    prevOwner = (Thread) debugOwnerField.get(thread);
                                                    debugOwnerField.set(thread, Thread.currentThread());
                                                } catch (Throwable ignored) {}

                                                try {
                                                    Method protCall = caller.getClass().getMethod("protectedCall", Class.forName("se.krka.kahlua.vm.KahluaThread"), Object.class, Object[].class);
                                                    protCall.invoke(caller, thread, closure, new Object[0]);
                                                    PZOLogger.success("[PZO Kahlua Bridge] Main Menu Beta Opt-In Tickbox UI injected into Kahlua via protectedCall");
                                                } finally {
                                                    if (debugOwnerField != null && prevOwner != null) {
                                                        try {
                                                            debugOwnerField.set(thread, prevOwner);
                                                        } catch (Throwable ignored) {}
                                                    }
                                                }
                                            }
                                        }

                                    } catch (Throwable t) {
                                        PZOLogger.warn("[PZO Kahlua Bridge] Main Menu Beta UI injection notice: " + t.getMessage());
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            PZOLogger.warn("[PZO Kahlua Bridge] Table binding warning: " + t.getMessage());
                        }

                        PZOLogger.success("[PZO Kahlua Bridge] Native Java methods and PZOEngine globals bound to LuaManager.env");
                        attached = true;
                        break;
                    }
                } catch (Throwable ignored) {}

                try {
                    Thread.sleep(i < 30 ? 100 : 250);
                } catch (InterruptedException ie) {
                    break;
                }
            }

            if (!attached) {
                PZOLogger.info("[PZO Kahlua Bridge] LuaManager polling finished (Fallback status files active)");
            }
        });

        bridgeHookThread.setDaemon(true);
        bridgeHookThread.setPriority(Thread.NORM_PRIORITY - 1);
        bridgeHookThread.setName("PZO-Kahlua-Bridge-Hook");
        bridgeHookThread.start();
    }

    // LuaManager.env exists before LuaEventManager has added every event to the Events table,
    // so injecting as soon as env is non-null can hit a nil Events.OnMainMenuEnter.
    private static boolean areLuaEventsRegistered(Object env) {
        try {
            Method envGet = env.getClass().getMethod("rawget", Object.class);
            Object events = envGet.invoke(env, "Events");
            if (events == null) return false;
            Method eventsGet = events.getClass().getMethod("rawget", Object.class);
            return eventsGet.invoke(events, "OnMainMenuEnter") != null
                && eventsGet.invoke(events, "OnResolutionChange") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void sanitizePrototype(Object protoObj, String defaultFilename) {
        if (protoObj == null) return;
        try {
            Class<?> protoClass = protoObj.getClass();
            Field fnField = protoClass.getField("filename");
            Object fnVal = fnField.get(protoObj);
            if (fnVal == null) {
                fnField.set(protoObj, defaultFilename != null ? defaultFilename : "media/lua/shared/event_callback.lua");
            }
            Field nameField = protoClass.getField("name");
            Object nameVal = nameField.get(protoObj);
            if (nameVal == null) {
                nameField.set(protoObj, "dynamic_callback");
            }
            Field fileField = protoClass.getField("file");
            Object fileVal = fileField.get(protoObj);
            if (fileVal == null) {
                fileField.set(protoObj, defaultFilename != null ? defaultFilename : "media/lua/shared/event_callback.lua");
            }
            Field protosField = protoClass.getField("prototypes");
            Object[] subProtos = (Object[]) protosField.get(protoObj);
            if (subProtos != null) {
                for (Object sub : subProtos) {
                    sanitizePrototype(sub, defaultFilename);
                }
            }
        } catch (Throwable ignored) {}
    }
}


