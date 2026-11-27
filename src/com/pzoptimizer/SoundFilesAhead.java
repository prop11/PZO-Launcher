package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sound files decoded while the save finishes loading (JVM_SoundFilesAhead).
 *
 * Sounds from the game's FMOD banks are fine, but a sound script clip that names a plain .ogg or .wav (most mods'
 * guns, voices and music, a few of the game's own) is decoded in full into memory the first time it plays:
 * FMODManager.loadSound calls FMOD_System_CreateSound on the game thread, so the first shot of a modded gun or the
 * first line of a voice pack hitches. Once the save is loaded, a low-priority thread now makes those sounds the same
 * way (same call, same 3D mode), smallest files first, and loadSound gets the ready one instead of decoding it. Its own
 * map of loaded sounds works as before.
 *
 * FMOD's core API is thread safe as the game sets it up (no FMOD_INIT_THREAD_UNSAFE). A sound the thread is decoding
 * when the game asks for it is waited for, never made twice; one the game already made is skipped. Only mode 16 (a
 * one-shot 3D sound, what the game uses in the world) is made ahead; looping previews go the stock way.
 *
 * Decoded audio is about ten times the .ogg's size and the game keeps every sound it made, so the ones made ahead stay
 * too. They're capped at a share of RAM (RAM / 32, at most 256 MB), and files over 2 MB (long music tracks) are left
 * to the game.
 */
public final class SoundFilesAhead {

    public static volatile boolean active = true;

    private static final long MODE_3D = 16L;
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    private static final long MAX_BUDGET_BYTES = 256L * 1024 * 1024;
    private static final long WAIT_MS = 3_000L;

    private static final Object LOCK = new Object();
    private static final Map<String, Long> ready = new HashMap<>();
    private static final Set<String> claimed = new HashSet<>();
    private static String decoding;
    private static long used;
    private static volatile boolean hooked;
    private static volatile int generation;

    public static volatile int listed, made, handedOut, skippedBig, skippedBudget, failed;

    private SoundFilesAhead() {}

    static void markHooked() {
        hooked = true;
    }

    /** Replaces javafmod.FMOD_System_CreateSound in FMODManager.loadSound. */
    public static long createSound(String name, long mode) {
        if (mode != MODE_3D || name == null) return fmod.javafmod.FMOD_System_CreateSound(name, mode);
        synchronized (LOCK) {
            long until = System.currentTimeMillis() + WAIT_MS;
            while (name.equals(decoding)) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) break;
                try {
                    LOCK.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            claimed.add(name);
            Long h = ready.remove(name);
            if (h != null) {
                handedOut++;
                return h;
            }
        }
        return fmod.javafmod.FMOD_System_CreateSound(name, mode);
    }

    /** GameLoadingState.SendDone / Done, through ModelShadersAhead.worldLoaded. */
    static void worldLoaded() {
        if (!active || !hooked || zombie.core.Core.soundDisabled || zombie.network.GameServer.server) return;
        if (!"GameLoadingThread".equals(Thread.currentThread().getName())) return;
        // That mod decodes these itself and its patch on loadSound comes first, so mine would go unused
        if (ViewpointStanddownGovernor.isViewpointDetected()) return;
        try {
            Set<String> paths = new LinkedHashSet<>();
            for (zombie.scripting.objects.GameSoundScript gs : zombie.scripting.ScriptManager.instance.getAllGameSounds()) {
                if (gs == null || gs.gameSound == null) continue;
                for (zombie.audio.GameSoundClip clip : gs.gameSound.clips) {
                    String f = clip == null ? null : clip.getFile();
                    if (f == null || f.isEmpty()) continue;
                    String abs = zombie.ZomboidFileSystem.instance.getAbsolutePath(f);
                    if (abs != null) paths.add(abs);
                }
            }
            List<String> list = new ArrayList<>(paths);
            listed = list.size();
            if (list.isEmpty()) return;
            int gen = ++generation;
            Thread t = new Thread(() -> decodeAll(list, gen), "PZO-SoundFilesAhead");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY + 1);
            t.start();
        } catch (Throwable t) {
            PZOLogger.warn("[SoundFilesAhead] left to the game: " + t);
        }
    }

    private static long budget() {
        long ram = Runtime.getRuntime().maxMemory();
        try {
            com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            ram = os.getTotalMemorySize();
        } catch (Throwable ignored) {
        }
        return Math.min(MAX_BUDGET_BYTES, ram / 32);
    }

    // Decoded size, roughly
    private static long estimate(String path, long bytes) {
        return path.toLowerCase(java.util.Locale.ROOT).endsWith(".wav") ? bytes : bytes * 10;
    }

    private static void decodeAll(List<String> paths, int gen) {
        long t0 = System.nanoTime();
        long cap = budget();
        List<long[]> sizes = new ArrayList<>(paths.size());
        for (int i = 0; i < paths.size(); i++) sizes.add(new long[] {new File(paths.get(i)).length(), i});
        sizes.sort((a, b) -> Long.compare(a[0], b[0]));
        for (long[] s : sizes) {
            if (gen != generation || !active || fmod.fmod.FMODManager.instance == null) break;
            String path = paths.get((int) s[1]);
            if (s[0] <= 0) continue;
            if (s[0] > MAX_FILE_BYTES) {
                skippedBig++;
                continue;
            }
            long est = estimate(path, s[0]);
            synchronized (LOCK) {
                if (claimed.contains(path) || ready.containsKey(path)) continue;
                if (used + est > cap) {
                    skippedBudget++;
                    continue;
                }
                decoding = path;
            }
            long h = 0L;
            try {
                h = fmod.javafmod.FMOD_System_CreateSound(path, MODE_3D);
            } catch (Throwable e) {
                h = 0L;
            } finally {
                synchronized (LOCK) {
                    decoding = null;
                    if (h != 0L) {
                        ready.put(path, h);
                        used += est;
                        made++;
                    } else {
                        failed++;
                    }
                    LOCK.notifyAll();
                }
            }
        }
        PZOLogger.info(String.format("[SoundFilesAhead] %d of %d sound files decoded ahead in %.0f ms (about %d MB; %d too long, %d over the cap, %d failed)",
            made, listed, (System.nanoTime() - t0) / 1e6, used >> 20, skippedBig, skippedBudget, failed));
    }

    public static String summary() {
        return "listed=" + listed + " made=" + made + " used=" + handedOut + " big=" + skippedBig + " cap=" + skippedBudget
            + " failed=" + failed + " mb=" + (used >> 20);
    }

    static boolean patchManager(ClassNode node) {
        int n = 0;
        for (MethodNode m : node.methods) {
            if (!"loadSound".equals(m.name)) continue;
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESTATIC && "fmod/javafmod".equals(mi.owner)
                        && "FMOD_System_CreateSound".equals(mi.name) && "(Ljava/lang/String;J)J".equals(mi.desc)) {
                    MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/SoundFilesAhead",
                        "createSound", "(Ljava/lang/String;J)J", false);
                    m.instructions.set(mi, call);
                    insn = call;
                    n++;
                }
            }
        }
        return n > 0;
    }
}
