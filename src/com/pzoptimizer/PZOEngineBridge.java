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

    public static boolean isBetaOptIn() {
        return PZOConfig.isBetaOptIn();
    }

    public static void setBetaOptIn(boolean optIn) {
        PZOConfig.setBetaOptIn(optIn);
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
                                                    UpdateChecker.UpdateResult res = UpdateChecker.checkForUpdatesSync(4000);
                                                    pushObj.invoke(mArgs[0], Boolean.valueOf(res.hasUpdate));
                                                    return 1;
                                                }
                                                return null;
                                            }
                                        );
                                        tableRawset.invoke(pzoTable, "checkForUpdates", checkUpdateFunc);

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
                                            "    btn:setVisible(true)\n" +
                                            "    MainScreen.instance:addChild(btn)\n" +
                                            "    MainScreen.instance.pzoBetaButton = btn\n" +
                                            "end\n" +
                                            "Events.OnMainMenuEnter.Add(function()\n" +
                                            "    addPZOBetaToggle()\n" +
                                            "    if MainScreen and not MainScreen.pzoHooked then\n" +
                                            "        MainScreen.pzoHooked = true\n" +
                                            "        local old_prerender = MainScreen.prerender\n" +
                                            "        MainScreen.prerender = function(self)\n" +
                                            "            old_prerender(self)\n" +
                                            "            if not self.pzoBetaButton then\n" +
                                            "                addPZOBetaToggle()\n" +
                                            "            end\n" +
                                            "        end\n" +
                                            "    end\n" +
                                            "end)\n" +
                                            "Events.OnResolutionChange.Add(function()\n" +
                                            "    if MainScreen and MainScreen.instance and MainScreen.instance.pzoBetaButton then\n" +
                                            "        local btn = MainScreen.instance.pzoBetaButton\n" +
                                            "        btn:setY((MainScreen.instance.height or getCore():getScreenHeight()) - btn.height - 18)\n" +
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
                                                Method protCall = caller.getClass().getMethod("protectedCall", Class.forName("se.krka.kahlua.vm.KahluaThread"), Object.class, Object[].class);
                                                protCall.invoke(caller, thread, closure, new Object[0]);
                                                PZOLogger.success("[PZO Kahlua Bridge] Main Menu Beta Opt-In Tickbox UI injected into Kahlua via protectedCall");
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

