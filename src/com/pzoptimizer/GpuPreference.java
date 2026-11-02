package com.pzoptimizer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * On Windows PCs with two graphics adapters (most gaming laptops) Windows often starts the game on the slow integrated
 * GPU. The usual fix (NvOptimusEnablement / AmdPowerXpressRequestHighPerformance exports) only works from the game's
 * own exe, so instead I set the per-app "High performance" choice from Settings > Display > Graphics for the running
 * exe (HKCU, no admin needed). Windows reads it when the process starts, so it applies from the next launch.
 *
 * Never touches a choice the player already made there. Turning the option off only removes the value if I added it
 * and it still says exactly what I wrote.
 */
public final class GpuPreference {

    private static final String KEY = "HKCU\\Software\\Microsoft\\DirectX\\UserGpuPreferences";
    private static final String HIGH_PERFORMANCE = "GpuPreference=2;";
    private static final long PROBE_WAIT_MS = 60_000L;

    private static Boolean requested = null;
    private static boolean workerRunning = false;

    private GpuPreference() {}

    public static synchronized void setActive(boolean value) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        requested = value;
        if (workerRunning) return;
        workerRunning = true;
        Thread t = new Thread(GpuPreference::run, "PZO-GpuPreference");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Applies whatever was asked for last; a toggle that comes in while this runs gets a second pass. */
    private static void run() {
        long deadline = System.currentTimeMillis() + PROBE_WAIT_MS;
        while (HardwareProbe.getGpuCount() < 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(250L);
            } catch (InterruptedException e) {
                break;
            }
        }
        boolean applied = false;
        boolean last = false;
        while (true) {
            boolean want;
            synchronized (GpuPreference.class) {
                if (requested == null || (applied && requested == last)) {
                    workerRunning = false;
                    return;
                }
                want = requested;
            }
            apply(want);
            applied = true;
            last = want;
        }
    }

    private static void apply(boolean want) {
        try {
            String exe = ProcessHandle.current().info().command().orElse("");
            if (!exe.toLowerCase().endsWith(".exe")) return;

            String current = readValue(exe);
            if (want) {
                if (current != null && current.contains("GpuPreference=")) {
                    if (!PZOConfig.isGpuPrefWritten()) PZOLogger.info("[GpuPreference] Windows already has a GPU choice for this game (" + current + "), left as it is");
                    return;
                }
                int gpus = HardwareProbe.getGpuCount();
                if (gpus < 2) {
                    PZOLogger.info("[GpuPreference] " + (gpus <= 0 ? "Graphics adapters unknown" : "One graphics adapter") + ", nothing to choose");
                    return;
                }
                // Windows keeps other per-app flags in the same value (like "AppStatus=4;"), so add mine to them
                String data = current == null ? HIGH_PERFORMANCE : current + (current.isEmpty() || current.endsWith(";") ? "" : ";") + HIGH_PERFORMANCE;
                if (reg("add", KEY, "/v", exe, "/t", "REG_SZ", "/d", data, "/f") == 0) {
                    PZOConfig.setGpuPrefWritten(true);
                    PZOLogger.success("[GpuPreference] " + gpus + " graphics adapters: Windows will start the game on the high-performance GPU from the next launch");
                } else {
                    PZOLogger.warn("[GpuPreference] Could not set the Windows GPU preference");
                }
            } else if (PZOConfig.isGpuPrefWritten()) {
                // Only take out my exact entry; anything else means the player changed it since
                if (current != null && current.contains(HIGH_PERFORMANCE)) {
                    String rest = current.replace(HIGH_PERFORMANCE, "");
                    int rc = rest.isEmpty() ? reg("delete", KEY, "/v", exe, "/f") : reg("add", KEY, "/v", exe, "/t", "REG_SZ", "/d", rest, "/f");
                    if (rc == 0) PZOLogger.info("[GpuPreference] Removed the GPU preference PZO added; Windows decides again from the next launch");
                }
                PZOConfig.setGpuPrefWritten(false);
            }
        } catch (Throwable t) {
            PZOLogger.warn("[GpuPreference] " + t);
        }
    }

    /** The value's data, or null if it doesn't exist. */
    private static String readValue(String exe) throws Exception {
        StringBuilder out = new StringBuilder();
        if (reg(out, "query", KEY, "/v", exe) != 0) return null;
        for (String line : out.toString().split("\r?\n")) {
            int i = line.indexOf("REG_SZ");
            if (i >= 0) return line.substring(i + "REG_SZ".length()).trim();
        }
        return null;
    }

    private static int reg(String... args) throws Exception {
        return reg(null, args);
    }

    private static int reg(StringBuilder out, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "reg.exe";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            in.transferTo(buf);
        }
        if (!p.waitFor(10, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return -1;
        }
        if (out != null) out.append(buf.toString());
        return p.exitValue();
    }
}
