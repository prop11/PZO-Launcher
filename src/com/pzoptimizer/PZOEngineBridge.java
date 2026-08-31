package com.pzoptimizer;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Dedicated Kahlua / LuaManager Java-Lua bridge for Project Zomboid.
 * Exposes native zero-overhead engine diagnostic and optimization methods directly to Lua scripts.
 */
public class PZOEngineBridge {

    private static volatile boolean initialized = false;
    private static volatile int cachedRamGb = 0;

    public static boolean isEnginePresent() {
        return true;
    }

    public static boolean isActive() {
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

    public static boolean isG1GC() {
        return true;
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

    /**
     * Asynchronously discovers and binds to zombie.Lua.LuaManager when Kahlua initializes.
     */
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

                    // 1. Try exposer if available
                    try {
                        Field exposerField = lmClass.getField("exposer");
                        Object exposer = exposerField.get(null);
                        if (exposer != null) {
                            Method exposeClassMethod = exposer.getClass().getMethod("exposeClass", Class.class);
                            exposeClassMethod.invoke(exposer, PZOEngineBridge.class);
                        }
                    } catch (Throwable ignored) {}

                    // 2. Bind directly into LuaManager.env
                    Field envField = lmClass.getField("env");
                    Object env = envField.get(null);
                    if (env != null) {
                        Method rawset = env.getClass().getMethod("rawset", Object.class, Object.class);

                        // Direct boolean and number globals
                        rawset.invoke(env, "PZOEngineActive", Boolean.TRUE);
                        rawset.invoke(env, "isPZOEngineActive", Boolean.TRUE);
                        rawset.invoke(env, "PZOEngineRAM", cachedRamGb);
                        rawset.invoke(env, "PZOEngineVersion", UpdateChecker.CURRENT_VERSION);

                        // Create KahluaTable for PZOEngine and PZOEngineBridge
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

                                    // Build dynamic JavaFunction proxies for Kahlua
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

                                    } catch (Throwable t) {
                                        PZOLogger.warn("[PZO Kahlua Bridge] JavaFunction proxy warning: " + t.getMessage());
                                    }

                                    rawset.invoke(env, "PZOEngine", pzoTable);
                                    rawset.invoke(env, "PZOEngineBridge", pzoTable);
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
}
