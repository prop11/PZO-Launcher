package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.FieldInsnNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.InsnNode;
import com.pzoptimizer.shaded.asm.tree.JumpInsnNode;
import com.pzoptimizer.shaded.asm.tree.LabelNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import zombie.core.VBO.GLVertexBufferObject;

/**
 * Maps the sprite ring buffers once instead of every batch (JVM_PersistentSpriteVbo).
 *
 * SpriteRenderer draws through a ring of 128 vertex + index buffers of 64 KB. Every time it moves to the next one (about
 * every 450 sprites, and at every frame start) stock orphans both buffers with glBufferData, maps them with
 * glMapBufferRange and unmaps them again before drawing: six driver calls that re-allocate storage, many times a frame
 * at a wide zoom. With GL 4.4 / ARB_buffer_storage the ring buffers get immutable storage that stays mapped (persistent,
 * coherent), so a batch just writes into memory the GPU already sees.
 *
 * The only thing that can go wrong is writing into a buffer the GPU is still reading, so each buffer gets a fence once
 * its draws are queued (at the next map: the ring unmaps a buffer, draws from it, then maps the next one) and the map
 * of a buffer waits on its fence first. 128 buffers deep, that wait is practically never reached.
 *
 * Read at ring creation, so it applies on the next launch. Without the extension, or if anything fails while setting a
 * buffer up, that buffer keeps the stock path.
 */
public final class PersistentSpriteVbo {

    public static volatile boolean enabled = true;
    public static final AtomicLong mapsAvoided = new AtomicLong();
    public static final AtomicLong fenceWaits = new AtomicLong();

    private static final int MAP_FLAGS = 0x2 | 0x40 | 0x80; // WRITE | PERSISTENT | COHERENT

    private static final class Slot {
        ByteBuffer buffer;
        int capacity;
        long fence;
    }

    // Render thread only: the ring is created, mapped, unmapped and drawn there
    private static final IdentityHashMap<GLVertexBufferObject, Slot> slots = new IdentityHashMap<>();
    private static final List<Slot> pending = new ArrayList<>();
    private static boolean announced;

    private static final class F {
        static final VarHandle SIZE;
        static {
            VarHandle s = null;
            try {
                s = MethodHandles.privateLookupIn(GLVertexBufferObject.class, MethodHandles.lookup())
                    .findVarHandle(GLVertexBufferObject.class, "size", long.class);
            } catch (Throwable t) {
                PZOLogger.warn("[PersistentSpriteVbo] buffer size not readable, stock buffers kept: " + t);
            }
            SIZE = s;
        }
    }

    private PersistentSpriteVbo() {}

    /** End of SpriteRenderer$RingBuffer.create(): moves the ring's buffers to persistent storage. */
    public static void register(GLVertexBufferObject[] vbo, GLVertexBufferObject[] ibo) {
        try {
            if (!enabled || !PZOConfig.isPersistentSpriteVboEnabled() || F.SIZE == null) return;
            if (!PersistentSpriteVboAsm.bufferHooked) {
                PZOLogger.info("[PersistentSpriteVbo] GLVertexBufferObject isn't hooked (another mod patches it), sprite buffers stay stock");
                return;
            }
            org.lwjgl.opengl.GLCapabilities caps = org.lwjgl.opengl.GL.getCapabilities();
            boolean core = caps.OpenGL44;
            if (!core && !caps.GL_ARB_buffer_storage) {
                PZOLogger.info("[PersistentSpriteVbo] No GL 4.4 / ARB_buffer_storage, sprite buffers stay stock");
                return;
            }
            int ok = 0;
            for (GLVertexBufferObject[] ring : new GLVertexBufferObject[][] {vbo, ibo}) {
                if (ring == null) continue;
                for (GLVertexBufferObject b : ring) {
                    if (b != null && setUp(b, core)) ok++;
                }
            }
            if (!announced || ok > 0) {
                announced = true;
                PZOLogger.success("[PersistentSpriteVbo] " + ok + " sprite ring buffers mapped once (persistent, coherent)");
            }
        } catch (Throwable t) {
            PZOLogger.warn("[PersistentSpriteVbo] Set-up failed, stock sprite buffers: " + t);
        }
    }

    private static boolean setUp(GLVertexBufferObject b, boolean core) {
        try {
            int type = typeOf(b);
            long size = (long) F.SIZE.get(b);
            if (size <= 0 || size > Integer.MAX_VALUE) return false;
            b.bind();
            if (core) {
                org.lwjgl.opengl.GL44.glBufferStorage(type, size, MAP_FLAGS);
            } else {
                org.lwjgl.opengl.ARBBufferStorage.glBufferStorage(type, size, MAP_FLAGS);
            }
            ByteBuffer buf = org.lwjgl.opengl.GL30.glMapBufferRange(type, 0L, size, MAP_FLAGS, null);
            if (buf == null) return false;
            Slot s = new Slot();
            s.buffer = buf.order(ByteOrder.nativeOrder());
            s.capacity = (int) size;
            slots.put(b, s);
            return true;
        } catch (Throwable t) {
            PZOLogger.warn("[PersistentSpriteVbo] One buffer left stock: " + t);
            return false;
        }
    }

    private static final VarHandle TYPE = typeHandle();

    private static VarHandle typeHandle() {
        try {
            return MethodHandles.privateLookupIn(GLVertexBufferObject.class, MethodHandles.lookup())
                .findVarHandle(GLVertexBufferObject.class, "type", int.class);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int typeOf(GLVertexBufferObject b) {
        return (int) TYPE.get(b);
    }

    /** Start of GLVertexBufferObject.map(): the persistent mapping, or null for the stock map. */
    public static ByteBuffer map(GLVertexBufferObject b) {
        if (slots.isEmpty()) return null;
        Slot s = slots.get(b);
        if (s == null) return null;
        try {
            if (!pending.isEmpty()) {
                // Their draws are queued by now; the fence lands after them
                for (Slot p : pending) {
                    if (p.fence == 0) p.fence = org.lwjgl.opengl.GL32.glFenceSync(0x9117, 0); // SYNC_GPU_COMMANDS_COMPLETE
                }
                pending.clear();
            }
            if (s.fence != 0) {
                int r = org.lwjgl.opengl.GL32.glClientWaitSync(s.fence, 0, 0L);
                if (r != 0x911A && r != 0x911C) { // not ALREADY_SIGNALED / CONDITION_SATISFIED
                    fenceWaits.incrementAndGet();
                    for (int i = 0; i < 1000 && r != 0x911A && r != 0x911C && r != 0x911D; i++) { // until done or WAIT_FAILED
                        r = org.lwjgl.opengl.GL32.glClientWaitSync(s.fence, 0x1, 1_000_000L); // FLUSH_COMMANDS, 1 ms
                    }
                }
                org.lwjgl.opengl.GL32.glDeleteSync(s.fence);
                s.fence = 0;
            }
            mapsAvoided.incrementAndGet();
            s.buffer.clear().limit(s.capacity);
            return s.buffer;
        } catch (Throwable t) {
            // A GL error here would leave the frame broken either way; drop every slot so the stock path takes over
            PZOLogger.warn("[PersistentSpriteVbo] Stood down, stock sprite buffers from now on: " + t);
            slots.clear();
            pending.clear();
            return null;
        }
    }

    /** Start of GLVertexBufferObject.unmap(): true when the buffer stays mapped. */
    public static boolean unmap(GLVertexBufferObject b) {
        if (slots.isEmpty()) return false;
        Slot s = slots.get(b);
        if (s == null) return false;
        if (!pending.contains(s)) pending.add(s);
        return true;
    }

    /** Start of GLVertexBufferObject.clear(): true skips the orphaning glBufferData, which immutable storage refuses. */
    public static boolean keep(GLVertexBufferObject b) {
        return !slots.isEmpty() && slots.containsKey(b);
    }

    /** Start of doDestroy(): forget the buffer (deleting it unmaps it). */
    public static void destroyed(GLVertexBufferObject b) {
        if (slots.isEmpty()) return;
        Slot s = slots.remove(b);
        if (s != null) {
            pending.remove(s);
            if (s.fence != 0) {
                try {
                    org.lwjgl.opengl.GL32.glDeleteSync(s.fence);
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
