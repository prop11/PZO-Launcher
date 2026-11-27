package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.BufferedInputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;
import org.lwjgl.opengl.ARBGetProgramBinary;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL41C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;

/**
 * Keeps the game's linked shader programs on disk between launches (JVM_ShaderCache, read at launch).
 *
 * Every launch the game compiles and links each shader program again: everything the main menu uses, then the world,
 * model and weather shaders as they first show up. Here ShaderUnit.compile hands its source to the driver but holds
 * back glCompileShader, and ShaderProgram.compile's glLinkProgram comes to me instead. The key is a hash of the exact
 * text each attached shader ended up with (read back with glGetShaderSource, so the game's own preprocessing, mod shader
 * patches from PZO Enhancements, early depth and the FSR gl_FragCoord rewrite are all in it), plus the renderer and
 * driver version. A stored binary for that key goes in with glProgramBinary; otherwise, or if the driver turns the
 * binary down, the held-back shaders compile and link as stock would and the result is stored.
 *
 * So nothing has to be cleared by hand: a game update, a changed mod shader, a PZOE toggle or a new graphics driver
 * all make new keys, and the old files age out past MAX_BYTES. Files live in Zomboid/PZO/shadercache.
 *
 * Off when the driver offers no binary formats (macOS) or lacks GL 4.1 / ARB_get_program_binary. Render thread only.
 */
public final class ShaderCache {

    public static volatile boolean active = PZOConfig.isShaderCacheEnabled();

    public static final AtomicLong hits = new AtomicLong(), misses = new AtomicLong(), rejected = new AtomicLong();
    public static final AtomicLong hitNanos = new AtomicLong(), missNanos = new AtomicLong();
    // Last time a program had to be compiled, for the on-screen bar
    public static volatile long lastMissNanos;
    // Where a hit's time goes: reading the shaders back and hashing them, and the driver taking the binary
    public static final AtomicLong keyNanos = new AtomicLong(), binaryNanos = new AtomicLong();
    public static final AtomicLong rawHits = new AtomicLong(), rawMisses = new AtomicLong();

    // Binaries read from disk on the writer thread at startup, newest first, so a hit doesn't wait for its file
    private static final ConcurrentHashMap<String, Object[]> preloaded = new ConcurrentHashMap<>();
    private static final long PRELOAD_BYTES = 128L << 20;
    // Attribute locations ShaderUnit.attach bound on a program before its link. Render thread.
    private static final java.util.HashMap<Integer, List<Object[]>> bindings = new java.util.HashMap<>();
    // Set while ShaderWarmup probes: a program that isn't stored is handed here instead of being compiled, and no
    // program is linked (the probe throws its programs away). Render thread.
    static java.util.function.Consumer<Capture> probe;

    // Linked programs ShaderWarmup made ahead, by key, for the game's ShaderProgram.compile to take over instead of
    // building its own. Render thread.
    private static final java.util.HashMap<String, Integer> pool = new java.util.HashMap<>();
    private static final int MAX_POOL = 800;
    public static final AtomicLong poolMade = new AtomicLong(), poolTaken = new AtomicLong();
    // In a holder: ShaderCache first loads while ShaderProgram itself is being patched, and touching ShaderProgram.class
    // from a static initialiser then defines it twice (LinkageError, no default shader, the game crashes)
    private static final class Ids {
        static final java.lang.invoke.VarHandle SHADER_ID = programIdField();
    }

    private static java.lang.invoke.VarHandle programIdField() {
        try {
            return java.lang.invoke.MethodHandles.privateLookupIn(zombie.core.opengl.ShaderProgram.class, java.lang.invoke.MethodHandles.lookup())
                .findVarHandle(zombie.core.opengl.ShaderProgram.class, "shaderId", int.class);
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean pooled(String key) {
        return pool.containsKey(key);
    }

    static int poolSize() {
        return pool.size();
    }

    /** What a program needs to be compiled away from the game: its key, shaders and attribute bindings. */
    static final class Capture {
        final String key;
        final int[] types;
        final String[] sources;
        final List<Object[]> attribs;

        Capture(String key, int[] types, String[] sources, List<Object[]> attribs) {
            this.key = key;
            this.types = types;
            this.sources = sources;
            this.attribs = attribs;
        }

        /** Stored already: made from its binary. */
        boolean fromBinary() {
            return sources == null;
        }
    }

    private static final int MAGIC = 0x505A5343; // "PZSC"
    private static final int FORMAT = 1;
    private static final long MAX_BYTES = 256L << 20;
    private static final int GL_NUM_PROGRAM_BINARY_FORMATS = 0x87FE;
    private static final int GL_PROGRAM_BINARY_LENGTH = 0x8741;
    private static final int GL_PROGRAM_BINARY_RETRIEVABLE_HINT = 0x8257;
    private static final int GL_LINK_STATUS = 0x8B82;
    private static final int GL_SHADER_TYPE = 0x8B4F;

    // Shader ids whose glCompileShader I held back, waiting for their program's link
    private static final BitSet held = new BitSet();
    private static final Set<String> stored = ConcurrentHashMap.newKeySet();
    // Programs read back but not on disk yet: key -> {format, bytes}. The same program can come round again first.
    private static final ConcurrentHashMap<String, Object[]> unwritten = new ConcurrentHashMap<>();
    private static ExecutorService writer;
    private static File dir;
    private static String driverKey;
    // 0 unknown, 1 usable, -1 off for this session
    private static int state;
    private static boolean core41;

    private ShaderCache() {}

    // Render thread: decides once per session whether binaries can be used at all
    private static boolean usable() {
        if (state != 0) return state > 0;
        state = -1;
        if (!active) return false;
        try {
            GLCapabilities caps = GL.getCapabilities();
            core41 = caps.OpenGL41;
            if (!core41 && !caps.GL_ARB_get_program_binary) {
                PZOLogger.info("[ShaderCache] driver has no program binaries (needs GL 4.1 or ARB_get_program_binary), off");
                return false;
            }
            if (GL11C.glGetInteger(GL_NUM_PROGRAM_BINARY_FORMATS) <= 0) {
                PZOLogger.info("[ShaderCache] driver offers no program binary formats, off");
                return false;
            }
            driverKey = GL11C.glGetString(GL11C.GL_VENDOR) + "|" + GL11C.glGetString(GL11C.GL_RENDERER) + "|" + GL11C.glGetString(GL11C.GL_VERSION);
            dir = new File(PZOEngineBridge.getZomboidDir(), "PZO" + File.separator + "shadercache");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                PZOLogger.warn("[ShaderCache] couldn't create " + dir + ", off");
                return false;
            }
            String[] names = dir.list();
            if (names != null) {
                for (String n : names) {
                    if (n.length() == 68 && n.endsWith(".bin")) stored.add(n.substring(0, 64));
                }
            }
            writer = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "PZO shader cache writer");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
            writer.execute(ShaderCache::prune);
            writer.execute(ShaderCache::preload);
            state = 1;
            PZOLogger.info("[ShaderCache] on: " + stored.size() + " stored programs, " + driverKey);
            return true;
        } catch (Throwable t) {
            PZOLogger.warn("[ShaderCache] off: " + t);
            return false;
        }
    }

    /** Whether binaries are in use this session (known once the render thread asked). */
    static boolean isOn() {
        return state > 0;
    }

    static boolean usableOnRenderThread() {
        return active && usable();
    }

    /** Replaces GL20.glBindAttribLocation in ShaderUnit.attach: the same call, remembered for ShaderWarmup's probe. */
    public static void bindAttrib(int program, int index, CharSequence name) {
        if (active && state >= 0) bindings.computeIfAbsent(program, k -> new ArrayList<>(4)).add(new Object[] {index, name.toString()});
        GL20C.glBindAttribLocation(program, index, name);
    }

    /** Replaces GL20.glCompileShader(id) in ShaderUnit.compile. */
    public static void compileShader(int shader, Object unit) {
        if (active && shader > 0 && usable() && ((zombie.core.opengl.ShaderUnit) unit).getParentShaderProgramGLID() != 0) {
            held.set(shader);
            return;
        }
        GL20C.glCompileShader(shader);
    }

    /** Replaces GL20.glLinkProgram(id) in ShaderProgram.compile; owner is the ShaderProgram. */
    public static void linkProgram(int program, Object owner) {
        link(program, owner);
    }

    public static void linkProgram(int program) {
        link(program, null);
    }

    // The game's program takes over one made ahead: its own (never compiled) shaders are attached to it so its
    // destroy() detaches and deletes them as usual, and its empty GL program goes
    private static boolean adopt(Object owner, int program, int ready, int[] shaders) {
        if (!(owner instanceof zombie.core.opengl.ShaderProgram) || Ids.SHADER_ID == null) return false;
        try {
            for (int s : shaders) GL20C.glAttachShader(ready, s);
            Ids.SHADER_ID.set(owner, ready);
            GL20C.glDeleteProgram(program);
            while (GL11C.glGetError() != GL11C.GL_NO_ERROR) {}
            WorldRenderScale.programRelinked(ready);
            return true;
        } catch (Throwable t) {
            PZOLogger.warn("[ShaderCache] couldn't hand over a program made ahead: " + t);
            return false;
        }
    }

    private static void link(int program, Object owner) {
        int[] shaders = attached(program);
        List<Object[]> attribs = bindings.remove(program);
        boolean anyHeld = false;
        for (int s : shaders) anyHeld |= held.get(s);
        if (probe != null) {
            // A probe's program: never linked, the probe throws it away
            for (int s : shaders) held.clear(s);
            if (!anyHeld) return;
            try {
                String k = key(shaders);
                probe.accept(stored.contains(k) ? new Capture(k, null, null, null) : capture(k, shaders, attribs));
            } catch (Throwable t) {
                PZOLogger.warn("[ShaderCache] couldn't key a probed program: " + t);
            }
            return;
        }
        if (!anyHeld) {
            WorldRenderScale.glLinkProgram(program);
            if (GL20C.glGetProgrami(program, GL_LINK_STATUS) == 0) logFailedLink(program, shaders);
            return;
        }
        long start = System.nanoTime();
        String key = null;
        try {
            key = key(shaders);
        } catch (Throwable t) {
            PZOLogger.warn("[ShaderCache] couldn't key a program, compiling it: " + t);
        }
        keyNanos.addAndGet(System.nanoTime() - start);
        if (key != null && owner != null && !pool.isEmpty()) {
            Integer ready = pool.remove(key);
            if (ready != null) {
                if (adopt(owner, program, ready, shaders)) {
                    for (int s : shaders) held.clear(s);
                    poolTaken.incrementAndGet();
                    return;
                }
                GL20C.glDeleteProgram(ready);
            }
        }
        if (key != null && stored.contains(key) && load(program, key)) {
            for (int s : shaders) held.clear(s);
            WorldRenderScale.programRelinked(program);
            hits.incrementAndGet();
            hitNanos.addAndGet(System.nanoTime() - start);
            return;
        }
        for (int s : shaders) {
            if (held.get(s)) {
                held.clear(s);
                GL20C.glCompileShader(s);
            }
        }
        if (key != null) programParameter(program, GL_PROGRAM_BINARY_RETRIEVABLE_HINT, 1);
        WorldRenderScale.glLinkProgram(program);
        if (GL20C.glGetProgrami(program, GL_LINK_STATUS) == 0) {
            logFailedLink(program, shaders);
        } else if (key != null) {
            save(program, key);
        }
        misses.incrementAndGet();
        missNanos.addAndGet(System.nanoTime() - start);
        lastMissNanos = System.nanoTime();
    }

    // The game only reports a failed link on its Shader debug channel, which is normally off, so a broken shader
    // (a mod patch the driver rejects) just drew nothing. Logged here with the driver's own messages.
    private static void logFailedLink(int program, int[] shaders) {
        try {
            StringBuilder sb = new StringBuilder("[ShaderCache] program ").append(program).append(" failed to link: ")
                .append(GL20C.glGetProgramInfoLog(program).trim());
            for (int s : shaders) {
                String src = GL20C.glGetShaderSource(s);
                int nl = src.indexOf("\n", src.indexOf("\n") + 1);
                sb.append(" | shader ").append(s).append(" compiled=").append(GL20C.glGetShaderi(s, GL20C.GL_COMPILE_STATUS))
                    .append(" (").append(nl > 0 ? src.substring(0, Math.min(nl, 60)) : "").append("): ")
                    .append(GL20C.glGetShaderInfoLog(s).trim());
            }
            String msg = sb.toString();
            PZOLogger.warn(msg.length() > 4000 ? msg.substring(0, 4000) + " ..." : msg);
        } catch (Throwable t) {
            PZOLogger.warn("[ShaderCache] program " + program + " failed to link (no log: " + t + ")");
        }
    }

    private static int[] attached(int program) {
        int count = GL20C.glGetProgrami(program, GL20C.GL_ATTACHED_SHADERS);
        if (count <= 0) return new int[0];
        IntBuffer n = MemoryUtil.memAllocInt(1);
        IntBuffer ids = MemoryUtil.memAllocInt(count);
        try {
            GL20C.glGetAttachedShaders(program, n, ids);
            int[] out = new int[n.get(0)];
            for (int i = 0; i < out.length; i++) out[i] = ids.get(i);
            return out;
        } finally {
            MemoryUtil.memFree(n);
            MemoryUtil.memFree(ids);
        }
    }

    // Attachment order isn't defined by GL, so the shaders are hashed in a sorted order
    private static String key(int[] shaders) throws Exception {
        List<String> parts = new ArrayList<>(shaders.length);
        for (int s : shaders) {
            parts.add(GL20C.glGetShaderi(s, GL_SHADER_TYPE) + ":" + GL20C.glGetShaderSource(s));
        }
        parts.sort(null);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(("PZSC" + FORMAT + "|" + driverKey).getBytes(StandardCharsets.UTF_8));
        for (String p : parts) {
            md.update((byte) 0);
            md.update(p.getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static boolean load(int program, String key) {
        File f = new File(dir, key + ".bin");
        int format;
        byte[] data;
        Object[] mem = unwritten.get(key);
        if (mem == null) mem = preloaded.get(key);
        if (mem != null) {
            format = (Integer) mem[0];
            data = (byte[]) mem[1];
        } else try {
            Object[] read = readFile(f);
            format = (Integer) read[0];
            data = (byte[]) read[1];
        } catch (Throwable t) {
            drop(key, "unreadable (" + t.getMessage() + ")");
            return false;
        }
        long b0 = System.nanoTime();
        ByteBuffer buf = MemoryUtil.memAlloc(data.length);
        try {
            buf.put(data).flip();
            if (core41) GL41C.glProgramBinary(program, format, buf);
            else ARBGetProgramBinary.glProgramBinary(program, format, buf);
        } finally {
            MemoryUtil.memFree(buf);
        }
        binaryNanos.addAndGet(System.nanoTime() - b0);
        // Clears any error a refused binary raised, so the game's own GL error checks don't pick it up
        while (GL11C.glGetError() != GL11C.GL_NO_ERROR) {}
        if (GL20C.glGetProgrami(program, GL_LINK_STATUS) == 0) {
            rejected.incrementAndGet();
            drop(key, "turned down by the driver");
            return false;
        }
        if (mem == null) touch(f);
        return true;
    }

    private static Object[] readFile(File f) throws java.io.IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            if (in.readInt() != MAGIC || in.readInt() != FORMAT) throw new java.io.IOException("not a cache file");
            int format = in.readInt();
            int len = in.readInt();
            long crc = in.readLong();
            if (len <= 0 || len > (64 << 20)) throw new java.io.IOException("bad length " + len);
            byte[] data = new byte[len];
            in.readFully(data);
            CRC32 c = new CRC32();
            c.update(data);
            if (c.getValue() != crc) throw new java.io.IOException("checksum mismatch");
            return new Object[] {format, data};
        }
    }

    // Writer thread, once at startup
    private static void preload() {
        try {
            File[] files = dir.listFiles((d, n) -> n.length() == 68 && n.endsWith(".bin"));
            if (files == null) return;
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            long t0 = System.nanoTime(), total = 0;
            int n = 0;
            for (File f : files) {
                if (total + f.length() > PRELOAD_BYTES) break;
                String key = f.getName().substring(0, 64);
                try {
                    Object[] read = readFile(f);
                    preloaded.put(key, read);
                    total += ((byte[]) read[1]).length;
                    n++;
                } catch (Throwable ignored) {
                    // load() finds it unreadable and drops it
                }
            }
            PZOLogger.info(String.format("[ShaderCache] %d stored programs read ahead (%d MB) in %.0f ms", n, total >> 20, (System.nanoTime() - t0) / 1e6));
        } catch (Throwable t) {
            PZOLogger.warn("[ShaderCache] read-ahead stopped: " + t);
        }
    }

    private static Capture capture(String key, int[] shaders, List<Object[]> attribs) {
        int[] types = new int[shaders.length];
        String[] sources = new String[shaders.length];
        for (int i = 0; i < shaders.length; i++) {
            types[i] = GL20C.glGetShaderi(shaders[i], GL_SHADER_TYPE);
            sources[i] = GL20C.glGetShaderSource(shaders[i]);
        }
        return new Capture(key, types, sources, attribs == null ? List.of() : attribs);
    }

    private static final int GL_COMPLETION_STATUS = 0x91B1;
    private static final int MAX_IN_FLIGHT = 48;

    /**
     * Compiles captured programs and stores their binaries, for ShaderWarmup. With KHR/ARB_parallel_shader_compile the
     * driver compiles a batch on its own threads while the loading screen keeps drawing; without it one at a time
     * within the frame's budget. Render thread, tick() once a frame until done() says so.
     */
    static final class Batch {
        private final List<Capture> todo;
        private final List<int[]> inFlight = new ArrayList<>();
        private final boolean parallel;
        private int next;
        int made, failed;

        // Programs made here go to the pool instead of being thrown away
        private final boolean keep;

        Batch(List<Capture> todo, boolean keep) {
            this.todo = todo;
            this.keep = keep;
            boolean par = false;
            try {
                GLCapabilities caps = GL.getCapabilities();
                if (caps.GL_KHR_parallel_shader_compile) {
                    org.lwjgl.opengl.KHRParallelShaderCompile.glMaxShaderCompilerThreadsKHR(0xFFFFFFFF);
                    par = true;
                } else if (caps.GL_ARB_parallel_shader_compile) {
                    org.lwjgl.opengl.ARBParallelShaderCompile.glMaxShaderCompilerThreadsARB(0xFFFFFFFF);
                    par = true;
                }
            } catch (Throwable t) {
                par = false;
            }
            this.parallel = par;
        }

        boolean parallel() {
            return parallel;
        }

        int total() {
            return todo.size();
        }

        int finished() {
            return made + failed;
        }

        boolean done() {
            return next >= todo.size() && inFlight.isEmpty();
        }

        void tick(long budgetNanos) {
            long end = System.nanoTime() + budgetNanos;
            if (!parallel) {
                while (next < todo.size() && System.nanoTime() < end) finish(submit(next++));
                return;
            }
            while (next < todo.size() && inFlight.size() < MAX_IN_FLIGHT) inFlight.add(submit(next++));
            for (int i = inFlight.size() - 1; i >= 0; i--) {
                int[] job = inFlight.get(i);
                if (job[0] != 0 && GL20C.glGetProgrami(job[0], GL_COMPLETION_STATUS) == 0) continue;
                inFlight.remove(i);
                finish(job);
                if (System.nanoTime() >= end) break;
            }
        }

        // {program, index of the capture, shaders...}
        private int[] submit(int index) {
            Capture c = todo.get(index);
            if (c.fromBinary()) return submitBinary(index, c);
            int[] job = new int[2 + c.types.length];
            job[1] = index;
            try {
                int prog = GL20C.glCreateProgram();
                job[0] = prog;
                for (int i = 0; i < c.types.length; i++) {
                    int s = GL20C.glCreateShader(c.types[i]);
                    GL20C.glShaderSource(s, c.sources[i]);
                    GL20C.glCompileShader(s);
                    GL20C.glAttachShader(prog, s);
                    job[2 + i] = s;
                }
                for (Object[] a : c.attribs) GL20C.glBindAttribLocation(prog, (Integer) a[0], (String) a[1]);
                programParameter(prog, GL_PROGRAM_BINARY_RETRIEVABLE_HINT, 1);
                GL20C.glLinkProgram(prog);
            } catch (Throwable t) {
                PZOLogger.warn("[ShaderCache] couldn't start a program ahead: " + t);
            }
            return job;
        }

        // A stored binary into a fresh program; the driver may take it in on its own threads like a link
        private int[] submitBinary(int index, Capture c) {
            int[] job = new int[] {0, index};
            Object[] mem = unwritten.get(c.key);
            if (mem == null) mem = preloaded.get(c.key);
            try {
                if (mem == null) mem = readFile(new File(dir, c.key + ".bin"));
            } catch (Throwable t) {
                drop(c.key, "unreadable (" + t.getMessage() + ")");
                return job;
            }
            byte[] data = (byte[]) mem[1];
            int prog = GL20C.glCreateProgram();
            job[0] = prog;
            long b0 = System.nanoTime();
            ByteBuffer buf = MemoryUtil.memAlloc(data.length);
            try {
                buf.put(data).flip();
                if (core41) GL41C.glProgramBinary(prog, (Integer) mem[0], buf);
                else ARBGetProgramBinary.glProgramBinary(prog, (Integer) mem[0], buf);
            } finally {
                MemoryUtil.memFree(buf);
            }
            binaryNanos.addAndGet(System.nanoTime() - b0);
            while (GL11C.glGetError() != GL11C.GL_NO_ERROR) {}
            return job;
        }

        private void finish(int[] job) {
            int prog = job[0];
            Capture c = todo.get(job[1]);
            boolean kept = false;
            try {
                if (prog != 0 && GL20C.glGetProgrami(prog, GL_LINK_STATUS) != 0) {
                    if (!c.fromBinary()) save(prog, c.key);
                    made++;
                    if (keep && pool.size() < MAX_POOL && !pool.containsKey(c.key)) {
                        // Only the program goes in: its shaders aren't needed once it's linked
                        for (int i = 2; i < job.length; i++) if (job[i] != 0) GL20C.glDetachShader(prog, job[i]);
                        pool.put(c.key, prog);
                        poolMade.incrementAndGet();
                        kept = true;
                    }
                } else {
                    if (c.fromBinary() && prog != 0) {
                        rejected.incrementAndGet();
                        drop(c.key, "turned down by the driver");
                    }
                    // The game makes it itself later and logs why it failed
                    failed++;
                }
            } finally {
                for (int i = 2; i < job.length; i++) if (job[i] != 0) GL20C.glDeleteShader(job[i]);
                if (prog != 0 && !kept) GL20C.glDeleteProgram(prog);
                while (GL11C.glGetError() != GL11C.GL_NO_ERROR) {}
            }
        }
    }

    /**
     * Builds a program from a vertex and a fragment source, through the cache, for code that drives GL itself (PZO
     * Enhancements). Returns the linked program, or 0 after logging why not. Render thread.
     */
    public static int buildProgram(String vertex, String fragment, String what) {
        String key = null;
        if (active && usable()) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                md.update(("PZSC" + FORMAT + "|raw|" + driverKey).getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(("35633:" + vertex).getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(("35632:" + fragment).getBytes(StandardCharsets.UTF_8));
                key = HexFormat.of().formatHex(md.digest());
            } catch (Throwable t) {
                key = null;
            }
        }
        long start = System.nanoTime();
        if (key != null && stored.contains(key)) {
            int p = GL20C.glCreateProgram();
            if (load(p, key)) {
                rawHits.incrementAndGet();
                hitNanos.addAndGet(System.nanoTime() - start);
                return p;
            }
            GL20C.glDeleteProgram(p);
        }
        int p = GL20C.glCreateProgram();
        for (int k = 0; k < 2; k++) {
            int s = GL20C.glCreateShader(k == 0 ? GL20C.GL_VERTEX_SHADER : GL20C.GL_FRAGMENT_SHADER);
            GL20C.glShaderSource(s, k == 0 ? vertex : fragment);
            GL20C.glCompileShader(s);
            if (GL20C.glGetShaderi(s, GL20C.GL_COMPILE_STATUS) == 0) {
                PZOLogger.warn(what + " " + (k == 0 ? "vertex" : "fragment") + " shader didn't compile: " + GL20C.glGetShaderInfoLog(s).trim());
                GL20C.glDeleteShader(s);
                GL20C.glDeleteProgram(p);
                return 0;
            }
            GL20C.glAttachShader(p, s);
            GL20C.glDeleteShader(s);
        }
        if (key != null) programParameter(p, GL_PROGRAM_BINARY_RETRIEVABLE_HINT, 1);
        GL20C.glLinkProgram(p);
        if (GL20C.glGetProgrami(p, GL_LINK_STATUS) == 0) {
            PZOLogger.warn(what + " shader didn't link: " + GL20C.glGetProgramInfoLog(p).trim());
            GL20C.glDeleteProgram(p);
            return 0;
        }
        if (key != null) save(p, key);
        rawMisses.incrementAndGet();
        missNanos.addAndGet(System.nanoTime() - start);
        lastMissNanos = System.nanoTime();
        return p;
    }

    private static void save(int program, String key) {
        try {
            int len = GL20C.glGetProgrami(program, GL_PROGRAM_BINARY_LENGTH);
            if (len <= 0) return;
            ByteBuffer buf = MemoryUtil.memAlloc(len);
            IntBuffer outLen = MemoryUtil.memAllocInt(1);
            IntBuffer outFormat = MemoryUtil.memAllocInt(1);
            byte[] data;
            int format;
            try {
                if (core41) GL41C.glGetProgramBinary(program, outLen, outFormat, buf);
                else ARBGetProgramBinary.glGetProgramBinary(program, outLen, outFormat, buf);
                int n = outLen.get(0);
                if (n <= 0) return;
                data = new byte[n];
                buf.get(0, data);
                format = outFormat.get(0);
            } finally {
                MemoryUtil.memFree(buf);
                MemoryUtil.memFree(outLen);
                MemoryUtil.memFree(outFormat);
            }
            unwritten.put(key, new Object[] {format, data});
            stored.add(key);
            writer.execute(() -> write(key, format, data));
        } catch (Throwable t) {
            PZOLogger.warn("[ShaderCache] couldn't read a program binary back: " + t);
        }
    }

    // Writer thread: temp file then rename, so a crash mid-write never leaves a half file under a real key
    private static void write(String key, int format, byte[] data) {
        File tmp = new File(dir, key + ".tmp");
        try {
            CRC32 c = new CRC32();
            c.update(data);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(data.length + 24);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeInt(FORMAT);
            out.writeInt(format);
            out.writeInt(data.length);
            out.writeLong(c.getValue());
            out.write(data);
            out.flush();
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(bytes.toByteArray());
            }
            Files.move(tmp.toPath(), new File(dir, key + ".bin").toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            unwritten.remove(key);
        } catch (Throwable t) {
            unwritten.remove(key);
            stored.remove(key);
            tmp.delete();
            PZOLogger.warn("[ShaderCache] couldn't store a program: " + t);
        }
    }

    private static void drop(String key, String why) {
        stored.remove(key);
        unwritten.remove(key);
        PZOLogger.info("[ShaderCache] stored program " + key.substring(0, 12) + " " + why + ", compiling it again");
        writer.execute(() -> new File(dir, key + ".bin").delete());
    }

    // Last-used order for pruning comes from the file dates
    private static void touch(File f) {
        writer.execute(() -> f.setLastModified(System.currentTimeMillis()));
    }

    private static void prune() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) {
            if (f.getName().endsWith(".tmp")) f.delete();
            else total += f.length();
        }
        if (total <= MAX_BYTES) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        int removed = 0;
        for (File f : files) {
            if (total <= MAX_BYTES * 3 / 4) break;
            if (!f.getName().endsWith(".bin")) continue;
            long len = f.length();
            if (f.delete()) {
                total -= len;
                removed++;
                String n = f.getName();
                stored.remove(n.substring(0, n.length() - 4));
            }
        }
        PZOLogger.info("[ShaderCache] pruned " + removed + " old programs, " + (total >> 20) + " MB kept");
    }

    private static void programParameter(int program, int pname, int value) {
        if (core41) GL41C.glProgramParameteri(program, pname, value);
        else ARBGetProgramBinary.glProgramParameteri(program, pname, value);
    }

    public static String summary() {
        long h = hits.get(), m = misses.get();
        return "Shader cache: " + (state > 0 ? "on" : state < 0 ? "off" : "not used yet") + ", " + h + " loaded ("
            + hitNanos.get() / 1_000_000L + " ms), " + m + " compiled (" + missNanos.get() / 1_000_000L + " ms), "
            + rejected.get() + " turned down, " + stored.size() + " stored; hits spent " + keyNanos.get() / 1_000_000L
            + " ms reading back and hashing, " + binaryNanos.get() / 1_000_000L + " ms in the driver; PZOE programs "
            + rawHits.get() + " loaded, " + rawMisses.get() + " compiled; made ahead " + poolMade.get() + ", handed over "
            + poolTaken.get();
    }

    // ShaderUnit.compile: glCompileShader(id) -> ShaderCache.compileShader(id, this)
    static boolean patchShaderUnit(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "compile", "()Z");
        if (m == null) return false;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && "com/pzoptimizer/ShaderCache".equals(mi.owner)) return false;
        }
        int n = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESTATIC && "glCompileShader".equals(mi.name)
                    && "(I)V".equals(mi.desc) && mi.owner.startsWith("org/lwjgl/opengl/GL")) {
                m.instructions.insertBefore(mi, new VarInsnNode(Opcodes.ALOAD, 0));
                MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/ShaderCache", "compileShader", "(ILjava/lang/Object;)V", false);
                m.instructions.set(mi, call);
                insn = call;
                n++;
            }
        }
        MethodNode attach = AsmSupport.findMethod(node, "attach", "()Z");
        if (attach != null && n == 1) {
            for (AbstractInsnNode insn = attach.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESTATIC && "glBindAttribLocation".equals(mi.name)
                        && "(IILjava/lang/CharSequence;)V".equals(mi.desc) && mi.owner.startsWith("org/lwjgl/opengl/GL")) {
                    MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/ShaderCache", "bindAttrib", "(IILjava/lang/CharSequence;)V", false);
                    attach.instructions.set(mi, call);
                    insn = call;
                }
            }
        }
        return n == 1;
    }

    // ShaderProgram.compile: glLinkProgram(id) -> ShaderCache.linkProgram(id)
    static boolean patchShaderProgram(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "compile", "()V");
        if (m == null) return false;
        int n = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESTATIC && "glLinkProgram".equals(mi.name)
                    && "(I)V".equals(mi.desc) && (mi.owner.startsWith("org/lwjgl/opengl/GL") || "com/pzoptimizer/WorldRenderScale".equals(mi.owner))) {
                MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/ShaderCache", "linkProgram", "(ILjava/lang/Object;)V", false);
                m.instructions.insertBefore(mi, new VarInsnNode(Opcodes.ALOAD, 0));
                m.instructions.set(mi, call);
                insn = call;
                n++;
            }
        }
        if (n == 1) {
            com.pzoptimizer.shaded.asm.tree.InsnList pre = new com.pzoptimizer.shaded.asm.tree.InsnList();
            pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
            pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/ShaderWarmup", "noteProgram", "(Ljava/lang/Object;)V", false));
            m.instructions.insert(pre);
        }
        return n == 1;
    }
}
