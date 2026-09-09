package com.pzoptimizer;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

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
        return HordeAnimationLODGovernor.getBoneTransformsSaved() + com.pzoptimizer.multicore.PZOMultiCoreEngine.getBonesSaved();
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

    public static boolean isBetaOptIn() {
        return PZOConfig.isBetaOptIn();
    }

    public static void setBetaOptIn(boolean optIn) {
        PZOConfig.setBetaOptIn(optIn);
    }

    public static boolean isMultithreadingNoticeAcknowledged() {
        return PZOConfig.isMultithreadingNoticeAcknowledged();
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

    public static void setJvmOption(String key, boolean value) {
        try {
            if ("JVM_GLStateOptimizer".equals(key)) {
                GLStateOptimizer.setEnabled(value);
            } else if ("JVM_StreamBufferBoost".equals(key)) {
                ChunkBufferPool.setEnabled(value);
            }
            PZOLogger.info("[PZO Bridge] setJvmOption: " + key + " = " + value);
        } catch (Throwable ignored) {}
    }

    public static void setJvmIntOption(String key, int value) {
        try {
            if ("JVM_ChunkCacheSize".equals(key)) {
                ChunkIngestionPacer.setMaxCachedChunks(value);
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

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;

        cachedRamGb = getOptimizedRAM();

        Thread bridgeHookThread = new Thread(() -> {
            boolean attached = false;
            // Poll for LuaManager.env initialization during boot (up to 30 seconds)
            for (int i = 0; i < 300; i++) {
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
                    if (env != null) {
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
                                            "    local modalW = math.min(740, scrW - 40)\n" +
                                            "    local modalH = math.min(490, scrH - 60)\n" +
                                            "    local modalX = (scrW - modalW) / 2\n" +
                                            "    local modalY = (scrH - modalH) / 2\n" +
                                            "    local ver = (PZOEngine and PZOEngine.getVersion and PZOEngine.getVersion()) or \"0.9.6\"\n" +
                                            "    local text = \" <CENTRE> <SIZE:medium> <RGB:0.25,0.95,0.45> Project Zomboid Optimiser (PZO v\" .. ver .. \") <LINE> \" ..\n" +
                                            "        \"<SIZE:large> <RGB:1,1,1> Multi-Threading Optimizations Active! <LINE> <LINE> \" ..\n" +
                                            "        \"<LEFT> <SIZE:small> <RGB:0.9,0.9,0.9> \" ..\n" +
                                            "        \"PZO's parallel multi-threaded engine is active. Multi-core chunk streaming, background island simulation, and native kernel acceleration are distributing workload across your CPU cores to maximize framerates and eliminate stutters. <LINE> <LINE> \" ..\n" +
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
                                            "Events.OnMainMenuEnter.Add(function()\n" +
                                            "    addPZOBetaToggle()\n" +
                                            "    showPZOMultithreadingNotice()\n" +
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
                                            "end)\n" +
                                            "Events.OnResolutionChange.Add(function()\n" +
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
                                            "end)\n";

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

                                        startLuaEventGovernor();
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
                    Thread.sleep(100);
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

    private static void startLuaEventGovernor() {
        Thread govThread = new Thread(() -> {
            while (true) {
                try {
                    Class<?> lemClass = Class.forName("zombie.Lua.LuaEventManager");
                    Field evListField = lemClass.getField("EventList");
                    java.util.ArrayList<?> evList = (java.util.ArrayList<?>) evListField.get(null);
                    if (evList != null) {
                        for (int i = 0; i < evList.size(); i++) {
                            Object ev = evList.get(i);
                            if (ev != null) {
                                Field cbField = ev.getClass().getField("callbacks");
                                java.util.ArrayList<?> cbList = (java.util.ArrayList<?>) cbField.get(ev);
                                if (cbList != null) {
                                    for (int j = 0; j < cbList.size(); j++) {
                                        Object cb = cbList.get(j);
                                        if (cb != null) {
                                            try {
                                                Field pField = cb.getClass().getField("prototype");
                                                Object p = pField.get(cb);
                                                if (p != null) {
                                                    sanitizePrototype(p, "media/lua/shared/event_callback.lua");
                                                }
                                            } catch (Throwable ignored) {}
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {}

                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }, "PZO-LuaEventRerouteGovernor");
        govThread.setDaemon(true);
        govThread.setPriority(Thread.MIN_PRIORITY);
        govThread.start();
    }
}

