package com.pzoptimizer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads the game classes earlier sessions used, on a low-priority thread once the main menu is up (JVM_ClassWarmup).
 *
 * The JVM loads a class the first time code touches it: read from the jar, parsed, sent through every agent's
 * transformer (PZO's own patches included) and verified. In play that lands in the frame that first needs it, so the
 * first zombie hit, the first car or the first time a window opens can hitch. Each session's game classes go into
 * Zomboid/PZO/class_warmup.txt, and the next launch loads that list in the background while the menu sits idle.
 * Loading only: no static initialiser runs, so the game behaves the same.
 *
 * Left alone: classes an installed ZombieBuddy mod patches (when they first load decides what PZO may change in them),
 * and the whole list once the game jar changed (a game update).
 */
public final class ClassWarmup {

    // Read from the config when the thread starts: record() runs inside the transformer, too early for that
    public static volatile boolean active = true;

    private static final String MENU_CLASS = "zombie/gameStates/MainScreenState";
    // A big modded session loads about 12k game classes
    private static final int MAX_CLASSES = 40_000;
    private static final long MENU_SETTLE_MS = 10_000L, SAVE_EVERY_MS = 300_000L;
    private static final int BATCH = 25;

    private static final Set<String> seen = ConcurrentHashMap.newKeySet();
    private static volatile boolean menuSeen;
    private static volatile boolean started;
    private static volatile int loaded, skipped, missing, listed;

    private ClassWarmup() {}

    /** From the transformer, for every class defined for the first time. */
    static void record(String internalName, ClassLoader loader) {
        if (internalName == null || !internalName.startsWith("zombie/")) return;
        if (MENU_CLASS.equals(internalName)) menuSeen = true;
        if (loader != ClassLoader.getSystemClassLoader() || seen.size() >= MAX_CLASSES) return;
        // Hidden and generated classes can't be loaded by name
        if (internalName.indexOf("$$") >= 0 || internalName.indexOf("/0x") >= 0) return;
        seen.add(internalName);
    }

    /** From PZOEntrypoint, before the game starts. */
    static synchronized void start() {
        if (started) return;
        started = true;
        Thread t = new Thread(ClassWarmup::run, "PZO-ClassWarmup");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(ClassWarmup::save, "PZO-ClassWarmupSave"));
        } catch (Throwable ignored) {
        }
    }

    private static void run() {
        try {
            active = PZOConfig.isClassWarmupEnabled();
            List<String> list = read();
            listed = list.size();
            while (!menuSeen) Thread.sleep(500L);
            Thread.sleep(MENU_SETTLE_MS);
            if (active && !list.isEmpty()) {
                long t0 = System.nanoTime();
                ClassLoader game = ClassLoader.getSystemClassLoader();
                int n = 0;
                for (String internal : list) {
                    if (!active) break;
                    if (seen.contains(internal) || ZombieBuddyCompat.hasTargets(internal)) {
                        skipped++;
                        continue;
                    }
                    try {
                        Class.forName(internal.replace('/', '.'), false, game);
                        loaded++;
                    } catch (Throwable e) {
                        missing++;
                    }
                    if (++n % BATCH == 0) Thread.sleep(2L);
                }
                PZOLogger.info(String.format("[ClassWarmup] %d of %d listed game classes loaded ahead in %.0f ms (%d already loaded or left alone, %d gone)",
                    loaded, listed, (System.nanoTime() - t0) / 1e6, skipped, missing));
            }
            while (true) {
                Thread.sleep(SAVE_EVERY_MS);
                save();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            PZOLogger.warn("[ClassWarmup] stopped: " + t);
        }
    }

    private static File file() {
        File dir = ZomboidDir.sub("PZO");
        dir.mkdirs();
        return new File(dir, "class_warmup.txt");
    }

    // The list is only good for the game jar it was made with
    private static String stamp() {
        try {
            // Found as a resource, so no game class loads early just for this
            java.net.URL url = ClassLoader.getSystemResource("zombie/core/Core.class");
            String spec = url.toString();
            File jar = new File(new java.net.URI(spec.substring(4, spec.indexOf("!/"))));
            return "# jar " + jar.length() + " " + jar.lastModified();
        } catch (Throwable t) {
            return "# jar unknown";
        }
    }

    private static List<String> read() {
        List<String> out = new ArrayList<>();
        try {
            File f = file();
            if (!f.isFile()) return out;
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.get(0).equals(stamp())) return out;
            for (int i = 1; i < lines.size() && out.size() < MAX_CLASSES; i++) {
                String s = lines.get(i).trim();
                if (s.startsWith("zombie/")) out.add(s);
            }
        } catch (Throwable t) {
            PZOLogger.warn("[ClassWarmup] could not read the list: " + t);
        }
        return out;
    }

    private static synchronized void save() {
        if (seen.isEmpty()) return;
        try {
            TreeSet<String> all = new TreeSet<>(read());
            all.addAll(seen);
            List<String> lines = new ArrayList<>(Math.min(all.size(), MAX_CLASSES) + 1);
            lines.add(stamp());
            for (String s : all) {
                if (lines.size() > MAX_CLASSES) break;
                lines.add(s);
            }
            File f = file();
            File tmp = new File(f.getPath() + ".tmp");
            Files.write(tmp.toPath(), lines, StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable t) {
            PZOLogger.warn("[ClassWarmup] could not write the list: " + t);
        }
    }

    public static String summary() {
        return "listed=" + listed + " loaded=" + loaded + " skipped=" + skipped + " missing=" + missing + " seen=" + seen.size();
    }
}
