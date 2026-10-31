package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.Reader;
import java.io.StringReader;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.LuaClosure;
import se.krka.kahlua.vm.Prototype;

/**
 * Settings screen "Keep compiled Lua between launches" (JVM_LuaCompileCache, next launch).
 *
 * Every launch and every save load the game compiles each Lua file it runs, its own and every mod's, from source
 * (LuaManager.RunLuaInternal -> LuaCompiler.loadis). The result depends only on the source text, the file name the
 * compiler stamps into each function (FuncState.currentFile / currentfullFile) and Core.debug (local variable lines),
 * so it is kept in Zomboid/PZO/luacache.bin under a SHA-1 of the text plus those three, and loaded from there next
 * time. Every Prototype field is stored (Kahlua's own dump drops the file names and locals), the closure is created by
 * the same constructor, and anything unreadable is compiled as before. The file is dropped whole when
 * projectzomboid.jar changes, and only the process holding its lock writes to it.
 */
public final class LuaCompileCache {

    public static volatile boolean active = PZOConfig.isLuaCompileCacheEnabled();
    public static final AtomicLong hits = new AtomicLong(), misses = new AtomicLong();
    public static final AtomicLong hitNanos = new AtomicLong(), compileNanos = new AtomicLong();

    private static final int MAGIC = 0x505A4C43; // "PZLC"
    private static final int FORMAT = 1;
    private static final long MAX_FILE_BYTES = 256L << 20;

    private static HashMap<String, byte[]> entries;
    private static RandomAccessFile out;
    private static FileLock lock;
    private static boolean broken;

    private LuaCompileCache() {}

    /** Replaces LuaCompiler.loadis(reader, name, env) in LuaManager.RunLuaInternal. */
    public static LuaClosure loadis(Reader reader, String name, KahluaTable env) throws IOException {
        if (!active || broken) return LuaCompiler.loadis(reader, name, env);
        String source = readAll(reader);
        String key;
        try {
            key = key(source);
        } catch (Throwable t) {
            return LuaCompiler.loadis(new StringReader(source), name, env);
        }
        synchronized (LuaCompileCache.class) {
            open();
            byte[] blob = entries == null ? null : entries.get(key);
            if (blob != null) {
                long t0 = System.nanoTime();
                try {
                    Prototype p = readPrototype(new DataInputStream(new ByteArrayInputStream(blob)));
                    LuaClosure c = new LuaClosure(p, env);
                    hits.incrementAndGet();
                    hitNanos.addAndGet(System.nanoTime() - t0);
                    return c;
                } catch (Throwable t) {
                    entries.remove(key);
                }
            }
        }
        long t0 = System.nanoTime();
        // Compile errors propagate exactly as before and nothing is stored
        LuaClosure c = LuaCompiler.loadis(new StringReader(source), name, env);
        compileNanos.addAndGet(System.nanoTime() - t0);
        misses.incrementAndGet();
        store(key, c.prototype);
        long n = hits.get() + misses.get();
        if (n % 1000 == 0) logStats();
        return c;
    }

    public static void logStats() {
        PZOLogger.info(String.format(java.util.Locale.ROOT,
            "[LuaCompileCache] %d files from cache (%.0f ms), %d compiled (%.0f ms)",
            hits.get(), hitNanos.get() / 1e6, misses.get(), compileNanos.get() / 1e6));
    }

    private static String readAll(Reader r) throws IOException {
        StringBuilder sb = new StringBuilder(8192);
        char[] buf = new char[8192];
        int n;
        while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        return sb.toString();
    }

    private static String key(String source) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] d = md.digest(source.getBytes(StandardCharsets.UTF_16LE));
        StringBuilder sb = new StringBuilder(64);
        for (byte b : d) sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        sb.append('|').append(zombie.core.Core.debug ? '1' : '0');
        sb.append('|').append(org.luaj.kahluafork.compiler.FuncState.currentFile);
        sb.append('|').append(org.luaj.kahluafork.compiler.FuncState.currentfullFile);
        return sb.toString();
    }

    // ---- File ---------------------------------------------------------------------------------------------------

    private static String gameStamp() {
        try {
            File jar = new File(LuaCompiler.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return jar.getName() + ":" + jar.length() + ":" + jar.lastModified();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static void open() {
        if (entries != null || broken) return;
        entries = new HashMap<>();
        try {
            File dir = new File(PZOEngineBridge.getZomboidDir(), "PZO");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                broken = true;
                return;
            }
            File file = new File(dir, "luacache.bin");
            String stamp = gameStamp();
            long valid = file.isFile() && file.length() <= MAX_FILE_BYTES ? readFile(file, stamp) : -1;

            RandomAccessFile raf = new RandomAccessFile(new File(dir, "luacache.lock"), "rw");
            FileLock l = raf.getChannel().tryLock();
            if (l == null) {
                raf.close();
                PZOLogger.info("[LuaCompileCache] Another game process holds the cache: reading only");
                return;
            }
            lock = l;
            out = new RandomAccessFile(file, "rw");
            if (valid < 0) {
                out.setLength(0);
                out.writeInt(MAGIC);
                out.writeInt(FORMAT);
                writeStringRaw(out, stamp);
                entries.clear();
            } else {
                out.setLength(valid);
                out.seek(valid);
            }
            PZOLogger.info("[LuaCompileCache] " + entries.size() + " compiled Lua files cached");
        } catch (Throwable t) {
            PZOLogger.warn("[LuaCompileCache] Cache unavailable, compiling as usual: " + t);
            closeQuietly();
        }
    }

    // Loads every complete record; returns the length of the valid part, or -1 to start over
    private static long readFile(File file, String stamp) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file), 1 << 16))) {
            if (in.readInt() != MAGIC || in.readInt() != FORMAT) return -1;
            String s = readStringRaw(in);
            if (!stamp.equals(s)) {
                PZOLogger.info("[LuaCompileCache] Game files changed: cache rebuilt");
                return -1;
            }
            long pos = 8 + 4 + 2L * s.length();
            while (true) {
                String key;
                byte[] blob;
                try {
                    key = readStringRaw(in);
                    int len = in.readInt();
                    if (len < 0 || len > (64 << 20)) break;
                    blob = new byte[len];
                    in.readFully(blob);
                } catch (EOFException eof) {
                    break;
                }
                entries.put(key, blob);
                pos += 4 + 2L * key.length() + 4 + blob.length;
            }
            return pos;
        } catch (Throwable t) {
            entries.clear();
            return -1;
        }
    }

    private static synchronized void store(String key, Prototype p) {
        if (out == null) return;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
            writePrototype(new DataOutputStream(bos), p);
            byte[] blob = bos.toByteArray();
            ByteArrayOutputStream rec = new ByteArrayOutputStream(blob.length + key.length() * 2 + 8);
            DataOutputStream d = new DataOutputStream(rec);
            writeStringRaw(d, key);
            d.writeInt(blob.length);
            d.write(blob);
            out.write(rec.toByteArray());
            entries.put(key, blob);
        } catch (Throwable t) {
            PZOLogger.warn("[LuaCompileCache] Could not store a compiled file, cache closed: " + t);
            closeQuietly();
        }
    }

    private static void closeQuietly() {
        try {
            if (out != null) out.close();
        } catch (Throwable ignored) {}
        out = null;
        try {
            if (lock != null) lock.release();
        } catch (Throwable ignored) {}
        lock = null;
    }

    // ---- Prototype encoding (every field, strings as raw UTF-16 so nothing is lost) -----------------------------

    static void writePrototype(DataOutputStream d, Prototype p) throws IOException {
        writeInts(d, p.code);
        d.writeInt(p.constants == null ? -1 : p.constants.length);
        if (p.constants != null) {
            for (Object o : p.constants) {
                if (o == null) {
                    d.writeByte(0);
                } else if (o instanceof Boolean b) {
                    d.writeByte(b ? 2 : 1);
                } else if (o instanceof Double v) {
                    d.writeByte(3);
                    d.writeLong(Double.doubleToRawLongBits(v));
                } else if (o instanceof String s) {
                    d.writeByte(4);
                    writeString(d, s);
                } else {
                    throw new IOException("constant of type " + o.getClass().getName());
                }
            }
        }
        d.writeInt(p.prototypes == null ? -1 : p.prototypes.length);
        if (p.prototypes != null) {
            for (Prototype c : p.prototypes) writePrototype(d, c);
        }
        d.writeInt(p.numParams);
        d.writeBoolean(p.isVararg);
        writeString(d, p.name);
        writeInts(d, p.lines);
        d.writeInt(p.numUpvalues);
        d.writeInt(p.maxStacksize);
        writeString(d, p.file);
        writeString(d, p.filename);
        d.writeInt(p.locvars == null ? -1 : p.locvars.length);
        if (p.locvars != null) {
            for (String s : p.locvars) writeString(d, s);
        }
        writeInts(d, p.locvarlines);
    }

    static Prototype readPrototype(DataInputStream d) throws IOException {
        Prototype p = new Prototype();
        p.code = readInts(d);
        int nk = d.readInt();
        if (nk >= 0) {
            p.constants = new Object[nk];
            for (int i = 0; i < nk; i++) {
                int t = d.readByte();
                p.constants[i] = switch (t) {
                    case 0 -> null;
                    case 1 -> Boolean.FALSE;
                    case 2 -> Boolean.TRUE;
                    case 3 -> Double.longBitsToDouble(d.readLong());
                    case 4 -> readString(d);
                    default -> throw new IOException("bad constant tag " + t);
                };
            }
        }
        int np = d.readInt();
        if (np >= 0) {
            p.prototypes = new Prototype[np];
            for (int i = 0; i < np; i++) p.prototypes[i] = readPrototype(d);
        }
        p.numParams = d.readInt();
        p.isVararg = d.readBoolean();
        p.name = readString(d);
        p.lines = readInts(d);
        p.numUpvalues = d.readInt();
        p.maxStacksize = d.readInt();
        p.file = readString(d);
        p.filename = readString(d);
        int nl = d.readInt();
        if (nl >= 0) {
            p.locvars = new String[nl];
            for (int i = 0; i < nl; i++) p.locvars[i] = readString(d);
        }
        p.locvarlines = readInts(d);
        return p;
    }

    private static void writeInts(DataOutputStream d, int[] a) throws IOException {
        d.writeInt(a == null ? -1 : a.length);
        if (a != null) for (int v : a) d.writeInt(v);
    }

    private static int[] readInts(DataInputStream d) throws IOException {
        int n = d.readInt();
        if (n < 0) return null;
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = d.readInt();
        return a;
    }

    private static void writeString(DataOutputStream d, String s) throws IOException {
        if (s == null) {
            d.writeInt(-1);
            return;
        }
        d.writeInt(s.length());
        d.writeChars(s);
    }

    private static String readString(DataInputStream d) throws IOException {
        int n = d.readInt();
        if (n < 0) return null;
        char[] c = new char[n];
        for (int i = 0; i < n; i++) c[i] = d.readChar();
        return new String(c);
    }

    // Keys and the header stamp: never null
    private static void writeStringRaw(java.io.DataOutput d, String s) throws IOException {
        d.writeInt(s.length());
        d.writeChars(s);
    }

    private static String readStringRaw(DataInputStream d) throws IOException {
        int n = d.readInt();
        if (n < 0 || n > (1 << 20)) throw new IOException("bad string length");
        char[] c = new char[n];
        for (int i = 0; i < n; i++) c[i] = d.readChar();
        return new String(c);
    }

    // ---- Patch --------------------------------------------------------------------------------------------------

    static boolean patchLuaManager(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "RunLuaInternal", "(Ljava/lang/String;Z)Ljava/lang/Object;");
        if (m == null) return false;
        int n = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESTATIC
                    && "se/krka/kahlua/luaj/compiler/LuaCompiler".equals(mi.owner) && "loadis".equals(mi.name)
                    && "(Ljava/io/Reader;Ljava/lang/String;Lse/krka/kahlua/vm/KahluaTable;)Lse/krka/kahlua/vm/LuaClosure;".equals(mi.desc)) {
                mi.owner = "com/pzoptimizer/LuaCompileCache";
                n++;
            }
        }
        return n == 1;
    }
}
