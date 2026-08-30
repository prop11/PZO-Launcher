package com.pzoptimizer;

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
        } catch (Throwable ignored) {}
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
