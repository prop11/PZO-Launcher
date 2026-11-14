package com.pzoptimizer;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.Locale;

import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * On Windows PZO loads itself as an agent from PZOEntrypoint instead of a json -agentlib. ProjectZomboid64.exe never
 * loads jli.dll and doesn't put jre64\bin on the DLL search path, so -agentlib:instrument / -javaagent die with "Can't
 * find dependent libraries" before the VM starts (pzo_native64.dll only worked because it called SetDllDirectory
 * first). From Java I can load jli.dll by full path, then have the JVM load instrument.dll through the JVMTI.agent_load
 * diagnostic command, which runs PZOptimAgent.agentmain. Both are the JRE's own signed DLLs, so Smart App Control lets
 * them through. Must run before any game class loads.
 */
final class AgentSelfLoader {

    private AgentSelfLoader() {}

    static void loadIfNeeded() {
        // Already loaded by a json agent arg (macOS/Linux, an unmigrated json, or a java.exe bench launch)
        if (PZOptimAgent.getInstrumentation() != null) return;
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return;

        long t0 = System.nanoTime();
        File bin = new File(System.getProperty("java.home"), "bin");
        File jli = new File(bin, "jli.dll");
        File instrument = new File(bin, "instrument.dll");
        File jar = ownJar();
        if (jar == null || !instrument.isFile()) {
            PZOLogger.warn("[PZO Agent] Can't self-load: " + (jar == null ? "PZOptimEngine.jar location unknown" : instrument + " missing"));
            return;
        }
        try {
            if (jli.isFile()) System.load(jli.getAbsolutePath());
        } catch (Throwable t) {
            PZOLogger.warn("[PZO Agent] Loading " + jli + " failed: " + t);
        }
        String result = null;
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            ObjectName dcmd = new ObjectName("com.sun.management:type=DiagnosticCommand");
            result = (String) mbs.invoke(dcmd, "jvmtiAgentLoad",
                new Object[] { new String[] { quote(instrument.getAbsolutePath()), quote(jar.getAbsolutePath()) } },
                new String[] { String[].class.getName() });
        } catch (Throwable t) {
            PZOLogger.warn("[PZO Agent] JVMTI.agent_load failed: " + t);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (PZOptimAgent.getInstrumentation() != null) {
            PZOLogger.success("[PZO Agent] Loaded through the JRE's instrument.dll from PZOEntrypoint (" + ms + " ms, no native bridge)");
        } else {
            PZOLogger.warn("[PZO Agent] Self-load did not start the agent" + (result == null || result.isBlank() ? "" : ": " + result.trim()));
        }
    }

    private static File ownJar() {
        try {
            File f = new File(AgentSelfLoader.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return f.isFile() ? f : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // The diagnostic command line splits on spaces unless the argument is quoted
    private static String quote(String s) {
        return s.indexOf(' ') >= 0 ? "\"" + s + "\"" : s;
    }
}
