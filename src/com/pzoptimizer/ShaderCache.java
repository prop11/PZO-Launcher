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
            state = 1;
            PZOLogger.info("[ShaderCache] on: " + stored.size() + " stored programs, " + driverKey);
            return true;
        } catch (Throwable t) {
            PZOLogger.warn("[ShaderCache] off: " + t);
            return false;
        }
    }

    /** Replaces GL20.glCompileShader(id) in ShaderUnit.compile. */
    public static void compileShader(int shader, Object unit) {
        if (active && shader > 0 && usable() && ((zombie.core.opengl.ShaderUnit) unit).getParentShaderProgramGLID() != 0) {
            held.set(shader);
            return;
        }
        GL20C.glCompileShader(shader);
    }

    /** Replaces GL20.glLinkProgram(id) in ShaderProgram.compile. */
    public static void linkProgram(int program) {
        int[] shaders = attached(program);
        boolean anyHeld = false;
        for (int s : shaders) anyHeld |= held.get(s);
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
        if (mem != null) {
            format = (Integer) mem[0];
            data = (byte[]) mem[1];
        } else try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            if (in.readInt() != MAGIC || in.readInt() != FORMAT) throw new java.io.IOException("not a cache file");
            format = in.readInt();
            int len = in.readInt();
            long crc = in.readLong();
            if (len <= 0 || len > (64 << 20)) throw new java.io.IOException("bad length " + len);
            data = new byte[len];
            in.readFully(data);
            CRC32 c = new CRC32();
            c.update(data);
            if (c.getValue() != crc) throw new java.io.IOException("checksum mismatch");
        } catch (Throwable t) {
            drop(key, "unreadable (" + t.getMessage() + ")");
            return false;
        }
        ByteBuffer buf = MemoryUtil.memAlloc(data.length);
        try {
            buf.put(data).flip();
            if (core41) GL41C.glProgramBinary(program, format, buf);
            else ARBGetProgramBinary.glProgramBinary(program, format, buf);
        } finally {
            MemoryUtil.memFree(buf);
        }
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
            + rejected.get() + " turned down, " + stored.size() + " stored";
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
                MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/ShaderCache", "linkProgram", "(I)V", false);
                m.instructions.set(mi, call);
                insn = call;
                n++;
            }
        }
        return n == 1;
    }
}
