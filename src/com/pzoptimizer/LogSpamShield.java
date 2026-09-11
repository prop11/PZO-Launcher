package com.pzoptimizer;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Project Zomboid Build 42 - Log Spam & PrintStream Lock Contention Shield.
 * In heavy modpacks, poorly optimized mods frequently execute debug logging inside
 * per-frame render or tick loops (e.g. LingLi_SCTD_LipsSkin logging 8,869 times).
 * Synchronous I/O and synchronized PrintStream locks create severe thread contention
 * between the render loop and main thread, driving frame times up to 100ms+.
 * This shield installs a zero-overhead deduplicating stream on System.out, System.err,
 * and DebugLog, muzzling repeating duplicate messages while periodically summarizing them.
 */
public final class LogSpamShield {

    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static volatile boolean debugLogHooked = false;
    private static DeduplicatingOutputStream filteringOut = null;
    private static DeduplicatingOutputStream filteringErr = null;

    public static void initialize() {
        if (!initialized.compareAndSet(false, true)) {
            return;
        }

        try {
            OutputStream origOut = System.out;
            OutputStream origErr = System.err;

            filteringOut = new DeduplicatingOutputStream(origOut, false);
            filteringErr = new DeduplicatingOutputStream(origErr, true);

            System.setOut(new PrintStream(filteringOut, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(filteringErr, true, StandardCharsets.UTF_8));

            hookDebugLog();

            Thread cleanerDaemon = new Thread(LogSpamShield::daemonLoop, "PZO-LogSpamCleaner");
            cleanerDaemon.setDaemon(true);
            cleanerDaemon.setPriority(Thread.MIN_PRIORITY);
            cleanerDaemon.start();

            PZOLogger.success("[LogSpamShield] Armed: Zero-Stall Console Deduplicator & Mutex Contention Shield");
        } catch (Throwable t) {
            PZOLogger.warn("[LogSpamShield] Initialization notice: " + t.getMessage());
        }
    }

    private static void hookDebugLog() {
        if (debugLogHooked) return;
        try {
            Class<?> debugLogClass = Class.forName("zombie.debug.DebugLog");
            Method getInstMethod = debugLogClass.getMethod("getInstance");
            Object inst = getInstMethod.invoke(null);
            if (inst != null && filteringOut != null && filteringErr != null) {
                Method setStdOut = debugLogClass.getMethod("setStdOut", OutputStream.class);
                Method setStdErr = debugLogClass.getMethod("setStdErr", OutputStream.class);
                setStdOut.invoke(inst, filteringOut);
                setStdErr.invoke(inst, filteringErr);
                debugLogHooked = true;
            }
        } catch (Throwable ignored) {}
    }

    private static void daemonLoop() {
        while (true) {
            try {
                Thread.sleep(3000);
                if (!debugLogHooked) {
                    hookDebugLog();
                }
                if (filteringOut != null) {
                    filteringOut.flushSummaries();
                }
                if (filteringErr != null) {
                    filteringErr.flushSummaries();
                }
            } catch (InterruptedException ie) {
                break;
            } catch (Throwable ignored) {}
        }
    }

    public static final class DeduplicatingOutputStream extends FilterOutputStream {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        private final boolean isErr;
        private final ConcurrentHashMap<String, Tracker> tracked = new ConcurrentHashMap<>();
        private final Object lock = new Object();

        private static final int MAX_BURST_PER_WINDOW = 3;
        private static final long WINDOW_MS = 1000L;
        private static final int MAX_CACHE_SIZE = 64;

        public DeduplicatingOutputStream(OutputStream out, boolean isErr) {
            super(out);
            this.isErr = isErr;
        }

        @Override
        public void write(int b) throws IOException {
            synchronized (lock) {
                if (b == '\n') {
                    flushBuffer();
                } else if (b != '\r') {
                    buffer.write(b);
                }
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            synchronized (lock) {
                for (int i = 0; i < len; i++) {
                    int ch = b[off + i];
                    if (ch == '\n') {
                        flushBuffer();
                    } else if (ch != '\r') {
                        buffer.write(ch);
                    }
                }
            }
        }

        private void flushBuffer() throws IOException {
            byte[] rawBytes = buffer.toByteArray();
            buffer.reset();

            String line = new String(rawBytes, StandardCharsets.UTF_8);
            if (line.isEmpty()) {
                out.write('\n');
                out.flush();
                return;
            }

            // Always allow PZO status/diagnostic messages without rate limiting
            if (line.contains("[PZO") || line.contains("[SUCCESS]") || line.contains("[WARN]")) {
                out.write(rawBytes);
                out.write('\n');
                out.flush();
                return;
            }

            String payload = extractPayload(line);
            if (payload.isEmpty()) {
                out.write(rawBytes);
                out.write('\n');
                out.flush();
                return;
            }

            long now = System.currentTimeMillis();
            Tracker tracker = tracked.compute(payload, (k, v) -> {
                if (v == null || (now - v.startTime > WINDOW_MS)) {
                    return new Tracker(now, 1, 0);
                }
                v.count++;
                return v;
            });

            if (tracker.count <= MAX_BURST_PER_WINDOW) {
                out.write(rawBytes);
                out.write('\n');
                out.flush();
            } else {
                tracker.suppressedTotal.incrementAndGet();
            }

            if (tracked.size() > MAX_CACHE_SIZE) {
                tracked.entrySet().removeIf(e -> now - e.getValue().startTime > 5000L);
            }
        }

        public void flushSummaries() {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Tracker> entry : tracked.entrySet()) {
                Tracker tracker = entry.getValue();
                long suppressed = tracker.suppressedTotal.getAndSet(0);
                if (suppressed > 0) {
                    String msg = "[PZO LogSpamShield] Suppressed " + suppressed + " repeating log entries: \"" + entry.getKey() + "\"\n";
                    try {
                        synchronized (lock) {
                            out.write(msg.getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        }
                    } catch (IOException ignored) {}
                }
            }
        }

        private static String extractPayload(String line) {
            if (line == null || line.isEmpty()) return "";
            int idx = line.indexOf("> :");
            if (idx >= 0 && idx + 3 < line.length()) {
                return line.substring(idx + 3).trim();
            }
            idx = line.indexOf("> ");
            if (idx >= 0 && idx + 2 < line.length()) {
                return line.substring(idx + 2).trim();
            }
            idx = line.indexOf("] ");
            if (idx >= 0 && idx + 2 < line.length() && idx < 30) {
                return line.substring(idx + 2).trim();
            }
            return line.trim();
        }

        private static final class Tracker {
            long startTime;
            int count;
            final AtomicLong suppressedTotal;

            Tracker(long startTime, int count, long initialSuppressed) {
                this.startTime = startTime;
                this.count = count;
                this.suppressedTotal = new AtomicLong(initialSuppressed);
            }
        }
    }
}
