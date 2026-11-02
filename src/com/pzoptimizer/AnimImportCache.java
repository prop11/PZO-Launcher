package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import org.lwjgl.util.vector.Quaternion;
import org.lwjgl.util.vector.Vector3f;
import zombie.core.skinnedmodel.animation.AnimationClip;
import zombie.core.skinnedmodel.animation.Keyframe;
import zombie.core.skinnedmodel.model.AnimationAsset;
import zombie.core.skinnedmodel.model.FileTask_AbstractLoadModel;
import zombie.core.skinnedmodel.model.FileTask_LoadAnimation;
import zombie.core.skinnedmodel.model.SkinningData;
import zombie.core.skinnedmodel.model.jassimp.ProcessedAiScene;

/**
 * Keeps imported animations between launches (JVM_AnimImportCache, applies next launch).
 *
 * Every launch the game pushes all of media/anims_X (2,200+ .X files, ~500 MB of text) plus mod animations through
 * Assimp, and the only thing it keeps is the keyframes. So I store those in Zomboid/PZO/animcache.bin, keyed on the
 * file's path, size and date, the import kind and a hash of the skeleton it's bound to. Floats are stored exactly and
 * vectors the importer shares stay shared. The file is thrown away when projectzomboid.jar changes, and every 64th hit
 * is imported again and compared; if anything differs the cache is emptied and switched off.
 */
public final class AnimImportCache {

    public static volatile boolean active = PZOConfig.isAnimImportCacheEnabled();
    public static final AtomicLong hits = new AtomicLong(), misses = new AtomicLong(), checks = new AtomicLong();
    public static final AtomicLong hitNanos = new AtomicLong(), importNanos = new AtomicLong();

    private static final int MAGIC = 0x505A4143; // "PZAC"
    private static final int FORMAT = 1;
    private static final long MAX_FILE_BYTES = 1L << 30;
    private static final int CHECK_EVERY = 64;

    private static final ConcurrentHashMap<String, long[]> index = new ConcurrentHashMap<>();
    private static volatile boolean opened, broken;
    private static FileChannel channel;
    private static boolean writable;
    private static long end;
    private static FileLock lock;

    private static Field fileNameField, animField, skeletonField, clipsField;
    private static Constructor<ProcessedAiScene> sceneCtor;
    private static Constructor<?> skeletonCtor;

    private AnimImportCache() {}

    public static ProcessedAiScene loadX(FileTask_AbstractLoadModel task) throws IOException {
        return load(task, 'X');
    }

    public static ProcessedAiScene loadFBX(FileTask_AbstractLoadModel task) throws IOException {
        return load(task, 'F');
    }

    public static ProcessedAiScene loadGLTF(FileTask_AbstractLoadModel task) throws IOException {
        return load(task, 'G');
    }

    private static ProcessedAiScene vanilla(FileTask_AbstractLoadModel task, char kind) throws IOException {
        return kind == 'X' ? task.loadX() : kind == 'F' ? task.loadFBX() : task.loadGLTF();
    }

    private static ProcessedAiScene load(FileTask_AbstractLoadModel task, char kind) throws IOException {
        if (!active || broken || !(task instanceof FileTask_LoadAnimation)) return vanilla(task, kind);
        String key = null;
        try {
            open();
            if (!broken) key = key(task, kind);
        } catch (Throwable t) {
            key = null;
        }
        if (key == null) return vanilla(task, kind);

        long[] at = index.get(key);
        if (at != null) {
            long t0 = System.nanoTime();
            HashMap<String, AnimationClip> clips = null;
            try {
                clips = decode(read(at));
            } catch (Throwable t) {
                PZOLogger.warn("[AnimImportCache] Unreadable entry, imported normally: " + t);
            }
            if (clips != null) {
                if (hits.incrementAndGet() % CHECK_EVERY == 1) {
                    ProcessedAiScene fresh = vanilla(task, kind);
                    checks.incrementAndGet();
                    String diff;
                    try {
                        HashMap<String, AnimationClip> real = clipsOf(fresh);
                        diff = real == null ? "import gave no clips" : compare(real, clips);
                    } catch (Throwable t) {
                        diff = t.toString();
                    }
                    if (diff != null) {
                        PZOLogger.warn("[AnimImportCache] Cached animation differs from a fresh import (" + key + ": " + diff
                            + "); cache turned off and emptied");
                        reset();
                        return fresh;
                    }
                }
                hitNanos.addAndGet(System.nanoTime() - t0);
                try {
                    return build(clips);
                } catch (Throwable t) {
                    PZOLogger.warn("[AnimImportCache] " + t + ", cache turned off");
                    broken = true;
                    return vanilla(task, kind);
                }
            }
        }

        long t0 = System.nanoTime();
        ProcessedAiScene scene = vanilla(task, kind);
        importNanos.addAndGet(System.nanoTime() - t0);
        misses.incrementAndGet();
        try {
            HashMap<String, AnimationClip> clips = clipsOf(scene);
            if (clips != null) store(key, encode(clips));
        } catch (Throwable t) {
            PZOLogger.warn("[AnimImportCache] Could not store an animation: " + t);
        }
        return scene;
    }

    public static void logStats() {
        PZOLogger.info(String.format(java.util.Locale.ROOT,
            "[AnimImportCache] %d animations from cache (%.0f ms), %d imported (%.0f ms), %d spot-checked",
            hits.get(), hitNanos.get() / 1e6, misses.get(), importNanos.get() / 1e6, checks.get()));
    }


    private static String key(FileTask_AbstractLoadModel task, char kind) throws Exception {
        String fileName = (String) fileNameField.get(task);
        if (fileName == null) return null;
        File f = new File(fileName);
        if (!f.isFile()) return null;
        AnimationAsset anim = (AnimationAsset) animField.get(task);
        SkinningData skin = anim == null || anim.assetParams == null || anim.assetParams.animationsMesh == null
            ? null : anim.assetParams.animationsMesh.skinningData;
        return f.getCanonicalPath() + "|" + f.length() + "|" + f.lastModified() + "|" + kind + "|" + skeletonHash(skin);
    }

    private static String skeletonHash(SkinningData skin) throws Exception {
        if (skin == null) return "none";
        StringBuilder sb = new StringBuilder();
        if (skin.boneIndices != null) {
            for (Map.Entry<String, Integer> e : new TreeMap<>(skin.boneIndices).entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append(';');
            }
        }
        sb.append('|');
        if (skin.skeletonHierarchy != null) {
            for (Integer i : skin.skeletonHierarchy) sb.append(i).append(',');
        }
        byte[] d = MessageDigest.getInstance("SHA-1").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(40);
        for (byte b : d) hex.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return hex.toString();
    }


    @SuppressWarnings("unchecked")
    private static HashMap<String, AnimationClip> clipsOf(ProcessedAiScene scene) throws Exception {
        if (scene == null) return null;
        Object skeleton = skeletonField.get(scene);
        return skeleton == null ? null : (HashMap<String, AnimationClip>) clipsField.get(skeleton);
    }

    @SuppressWarnings("unchecked")
    private static ProcessedAiScene build(HashMap<String, AnimationClip> clips) throws Exception {
        Object skeleton = skeletonCtor.newInstance();
        ((HashMap<String, AnimationClip>) clipsField.get(skeleton)).putAll(clips);
        ProcessedAiScene scene = sceneCtor.newInstance();
        skeletonField.set(scene, skeleton);
        return scene;
    }


    static byte[] encode(HashMap<String, AnimationClip> clips) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 16);
        DataOutputStream d = new DataOutputStream(bos);
        IdentityHashMap<Object, Integer> refs = new IdentityHashMap<>();
        HashMap<String, Integer> strings = new HashMap<>();
        d.writeInt(clips.size());
        for (Map.Entry<String, AnimationClip> e : clips.entrySet()) {
            AnimationClip c = e.getValue();
            writeString(d, strings, e.getKey());
            writeString(d, strings, c.name);
            d.writeFloat(c.getDuration());
            d.writeBoolean(c.keepLastFrame);
            d.writeBoolean(c.isRagdoll);
            Keyframe[] frames = c.getKeyframes();
            d.writeInt(frames.length);
            for (Keyframe k : frames) {
                d.writeInt(k.bone);
                writeString(d, strings, k.boneName);
                d.writeFloat(k.time);
                writeVec(d, refs, k.position);
                writeQuat(d, refs, k.rotation);
                writeVec(d, refs, k.scale);
            }
        }
        d.flush();
        byte[] raw = bos.toByteArray();
        Deflater def = new Deflater(1);
        try {
            def.setInput(raw);
            def.finish();
            ByteArrayOutputStream z = new ByteArrayOutputStream(raw.length / 2 + 64);
            DataOutputStream zd = new DataOutputStream(z);
            zd.writeInt(raw.length);
            byte[] buf = new byte[1 << 16];
            while (!def.finished()) {
                int n = def.deflate(buf);
                zd.write(buf, 0, n);
            }
            zd.flush();
            return z.toByteArray();
        } finally {
            def.end();
        }
    }

    static HashMap<String, AnimationClip> decode(byte[] blob) throws Exception {
        int rawLen = ((blob[0] & 255) << 24) | ((blob[1] & 255) << 16) | ((blob[2] & 255) << 8) | (blob[3] & 255);
        if (rawLen < 4 || rawLen > (256 << 20)) throw new IOException("bad length");
        byte[] raw = new byte[rawLen];
        Inflater inf = new Inflater();
        try {
            inf.setInput(blob, 4, blob.length - 4);
            int n = 0;
            while (n < rawLen) {
                int r = inf.inflate(raw, n, rawLen - n);
                if (r == 0 && (inf.finished() || inf.needsInput())) break;
                n += r;
            }
            if (n != rawLen) throw new IOException("short entry");
        } finally {
            inf.end();
        }
        DataInputStream d = new DataInputStream(new ByteArrayInputStream(raw));
        ArrayList<Object> refs = new ArrayList<>();
        ArrayList<String> strings = new ArrayList<>();
        int count = d.readInt();
        HashMap<String, AnimationClip> clips = new HashMap<>();
        ArrayList<Keyframe> frames = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String mapKey = readString(d, strings);
            String name = readString(d, strings);
            float duration = d.readFloat();
            boolean keepLast = d.readBoolean();
            boolean ragdoll = d.readBoolean();
            int n = d.readInt();
            frames.clear();
            for (int j = 0; j < n; j++) {
                Keyframe k = new Keyframe();
                k.bone = d.readInt();
                k.boneName = readString(d, strings);
                k.time = d.readFloat();
                k.position = readVec(d, refs);
                k.rotation = readQuat(d, refs);
                k.scale = readVec(d, refs);
                frames.add(k);
            }
            clips.put(mapKey, new AnimationClip(duration, frames, name, keepLast, ragdoll));
        }
        return clips;
    }

    private static void writeString(DataOutputStream d, HashMap<String, Integer> table, String s) throws IOException {
        if (s == null) {
            d.writeInt(-1);
            return;
        }
        Integer id = table.get(s);
        if (id != null) {
            d.writeInt(id);
            return;
        }
        table.put(s, table.size());
        d.writeInt(table.size() - 1);
        d.writeInt(s.length());
        d.writeChars(s);
    }

    private static String readString(DataInputStream d, ArrayList<String> table) throws IOException {
        int id = d.readInt();
        if (id < 0) return null;
        if (id < table.size()) return table.get(id);
        if (id != table.size()) throw new IOException("bad string ref");
        int n = d.readInt();
        if (n < 0 || n > (1 << 16)) throw new IOException("bad string length");
        char[] c = new char[n];
        for (int i = 0; i < n; i++) c[i] = d.readChar();
        String s = new String(c);
        table.add(s);
        return s;
    }

    // Refs share one id space: -1 null, an earlier id = the same object again, the next id = a new object follows
    private static boolean writeRef(DataOutputStream d, IdentityHashMap<Object, Integer> refs, Object o) throws IOException {
        if (o == null) {
            d.writeInt(-1);
            return false;
        }
        Integer id = refs.get(o);
        if (id != null) {
            d.writeInt(id);
            return false;
        }
        refs.put(o, refs.size());
        d.writeInt(refs.size() - 1);
        return true;
    }

    private static void writeVec(DataOutputStream d, IdentityHashMap<Object, Integer> refs, Vector3f v) throws IOException {
        if (!writeRef(d, refs, v)) return;
        d.writeFloat(v.x);
        d.writeFloat(v.y);
        d.writeFloat(v.z);
    }

    private static void writeQuat(DataOutputStream d, IdentityHashMap<Object, Integer> refs, Quaternion q) throws IOException {
        if (!writeRef(d, refs, q)) return;
        d.writeFloat(q.x);
        d.writeFloat(q.y);
        d.writeFloat(q.z);
        d.writeFloat(q.w);
    }

    private static Vector3f readVec(DataInputStream d, ArrayList<Object> refs) throws IOException {
        int id = d.readInt();
        if (id < 0) return null;
        if (id < refs.size()) return (Vector3f) refs.get(id);
        if (id != refs.size()) throw new IOException("bad ref");
        Vector3f v = new Vector3f(d.readFloat(), d.readFloat(), d.readFloat());
        refs.add(v);
        return v;
    }

    private static Quaternion readQuat(DataInputStream d, ArrayList<Object> refs) throws IOException {
        int id = d.readInt();
        if (id < 0) return null;
        if (id < refs.size()) return (Quaternion) refs.get(id);
        if (id != refs.size()) throw new IOException("bad ref");
        Quaternion q = new Quaternion(d.readFloat(), d.readFloat(), d.readFloat(), d.readFloat());
        refs.add(q);
        return q;
    }

    /** null when equal, else a short description of the first difference. */
    static String compare(HashMap<String, AnimationClip> a, HashMap<String, AnimationClip> b) {
        if (!a.keySet().equals(b.keySet())) return "clip names";
        for (Map.Entry<String, AnimationClip> e : a.entrySet()) {
            AnimationClip x = e.getValue(), y = b.get(e.getKey());
            if (!java.util.Objects.equals(x.name, y.name) || bits(x.getDuration()) != bits(y.getDuration())
                    || x.keepLastFrame != y.keepLastFrame || x.isRagdoll != y.isRagdoll) return "clip " + e.getKey();
            Keyframe[] p = x.getKeyframes(), q = y.getKeyframes();
            if (p.length != q.length) return "keyframe count in " + e.getKey();
            for (int i = 0; i < p.length; i++) {
                Keyframe k = p[i], l = q[i];
                if (k.bone != l.bone || !java.util.Objects.equals(k.boneName, l.boneName) || bits(k.time) != bits(l.time)
                        || !sameVec(k.position, l.position) || !sameQuat(k.rotation, l.rotation) || !sameVec(k.scale, l.scale)) {
                    return "keyframe " + i + " in " + e.getKey();
                }
            }
        }
        return null;
    }

    private static int bits(float f) {
        return Float.floatToIntBits(f);
    }

    private static boolean sameVec(Vector3f a, Vector3f b) {
        if (a == null || b == null) return a == b;
        return bits(a.x) == bits(b.x) && bits(a.y) == bits(b.y) && bits(a.z) == bits(b.z);
    }

    private static boolean sameQuat(Quaternion a, Quaternion b) {
        if (a == null || b == null) return a == b;
        return bits(a.x) == bits(b.x) && bits(a.y) == bits(b.y) && bits(a.z) == bits(b.z) && bits(a.w) == bits(b.w);
    }

    // File layout: header, then key / length / blob records. Only the offsets are kept in memory.

    private static String gameStamp() {
        try {
            File jar = new File(ProcessedAiScene.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return jar.getName() + ":" + jar.length() + ":" + jar.lastModified();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static synchronized void open() {
        if (opened) return;
        opened = true;
        if (initReflection()) openFile();
    }

    static synchronized boolean initReflection() {
        if (sceneCtor != null) return true;
        try {
            Field fn = FileTask_AbstractLoadModel.class.getDeclaredField("fileName");
            Field an = FileTask_LoadAnimation.class.getDeclaredField("anim");
            Field sk = ProcessedAiScene.class.getDeclaredField("skeleton");
            Class<?> skClass = sk.getType();
            Field cl = skClass.getDeclaredField("clips");
            Constructor<ProcessedAiScene> sc = ProcessedAiScene.class.getDeclaredConstructor();
            Constructor<?> kc = skClass.getDeclaredConstructor();
            for (java.lang.reflect.AccessibleObject o : new java.lang.reflect.AccessibleObject[] { fn, an, sk, cl, sc, kc }) {
                o.setAccessible(true);
            }
            fileNameField = fn;
            animField = an;
            skeletonField = sk;
            clipsField = cl;
            sceneCtor = sc;
            skeletonCtor = kc;
            return true;
        } catch (Throwable t) {
            PZOLogger.warn("[AnimImportCache] Game code changed, animations import as usual: " + t);
            broken = true;
            return false;
        }
    }

    private static synchronized void openFile() {
        try {
            File dir = new File(PZOEngineBridge.getZomboidDir(), "PZO");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                broken = true;
                return;
            }
            File file = new File(dir, "animcache.bin");
            RandomAccessFile lockFile = new RandomAccessFile(new File(dir, "animcache.lock"), "rw");
            FileLock l = lockFile.getChannel().tryLock();
            if (l == null) lockFile.close();
            lock = l;
            writable = l != null;
            channel = new RandomAccessFile(file, writable ? "rw" : "r").getChannel();
            String stamp = gameStamp();
            long valid = channel.size() <= MAX_FILE_BYTES ? readIndex(stamp) : -1;
            if (valid < 0) {
                index.clear();
                if (!writable) {
                    broken = true;
                    return;
                }
                channel.truncate(0);
                ByteArrayOutputStream h = new ByteArrayOutputStream();
                DataOutputStream d = new DataOutputStream(h);
                d.writeInt(MAGIC);
                d.writeInt(FORMAT);
                d.writeInt(stamp.length());
                d.writeChars(stamp);
                d.flush();
                channel.write(ByteBuffer.wrap(h.toByteArray()), 0);
                end = h.size();
            } else {
                end = valid;
                if (writable) channel.truncate(valid);
            }
            PZOLogger.info("[AnimImportCache] " + index.size() + " imported animations cached"
                + (writable ? "" : " (another game process holds the cache: reading only)"));
        } catch (Throwable t) {
            PZOLogger.warn("[AnimImportCache] Cache unavailable, animations import as usual: " + t);
            broken = true;
        }
    }

    // Indexes every complete record; returns the length of the valid part, or -1 to start over
    private static long readIndex(String stamp) {
        try {
            long size = channel.size();
            DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(
                java.nio.channels.Channels.newInputStream(channel.position(0)), 1 << 16));
            if (size < 12 || in.readInt() != MAGIC || in.readInt() != FORMAT) return -1;
            int n = in.readInt();
            if (n < 0 || n > 4096) return -1;
            char[] c = new char[n];
            for (int i = 0; i < n; i++) c[i] = in.readChar();
            if (!stamp.equals(new String(c))) {
                PZOLogger.info("[AnimImportCache] Game files changed: cache rebuilt");
                return -1;
            }
            long pos = 12 + 2L * n;
            while (pos + 8 <= size) {
                int kn = in.readInt();
                if (kn < 0 || kn > 8192 || pos + 4 + 2L * kn + 4 > size) break;
                char[] k = new char[kn];
                for (int i = 0; i < kn; i++) k[i] = in.readChar();
                int len = in.readInt();
                long blobAt = pos + 4 + 2L * kn + 4;
                if (len <= 4 || blobAt + len > size) break;
                in.skipNBytes(len);
                index.put(new String(k), new long[] { blobAt, len });
                pos = blobAt + len;
            }
            return pos;
        } catch (Throwable t) {
            index.clear();
            return -1;
        }
    }

    private static byte[] read(long[] at) throws IOException {
        byte[] blob = new byte[(int) at[1]];
        ByteBuffer bb = ByteBuffer.wrap(blob);
        long pos = at[0];
        while (bb.hasRemaining()) {
            int n = channel.read(bb, pos + bb.position());
            if (n < 0) throw new IOException("cache file truncated");
        }
        return blob;
    }

    private static synchronized void store(String key, byte[] blob) throws IOException {
        if (!writable || broken || channel == null || index.containsKey(key)) return;
        if (end + blob.length + 2L * key.length() + 8 > MAX_FILE_BYTES) return;
        ByteBuffer rec = ByteBuffer.allocate(4 + 2 * key.length() + 4 + blob.length);
        rec.putInt(key.length());
        for (int i = 0; i < key.length(); i++) rec.putChar(key.charAt(i));
        rec.putInt(blob.length);
        rec.put(blob);
        rec.flip();
        long at = end;
        while (rec.hasRemaining()) at += channel.write(rec, at);
        index.put(key, new long[] { end + 4 + 2L * key.length() + 4, blob.length });
        end = at;
    }

    private static synchronized void reset() {
        broken = true;
        index.clear();
        try {
            if (writable && channel != null) channel.truncate(0);
        } catch (Throwable ignored) {}
    }


    static boolean patchLoadModel(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "call", "()Ljava/lang/Object;");
        if (m == null) return false;
        String owner = "zombie/core/skinnedmodel/model/FileTask_AbstractLoadModel";
        String ret = "()Lzombie/core/skinnedmodel/model/jassimp/ProcessedAiScene;";
        String to = "(L" + owner + ";)Lzombie/core/skinnedmodel/model/jassimp/ProcessedAiScene;";
        int n = AsmSupport.redirectCalls(m, owner, "loadX", ret, "com/pzoptimizer/AnimImportCache", "loadX", to)
            + AsmSupport.redirectCalls(m, owner, "loadFBX", ret, "com/pzoptimizer/AnimImportCache", "loadFBX", to)
            + AsmSupport.redirectCalls(m, owner, "loadGLTF", ret, "com/pzoptimizer/AnimImportCache", "loadGLTF", to);
        return n == 3;
    }
}
