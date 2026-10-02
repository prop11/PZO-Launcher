package com.pzoptimizer;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Project Zomboid Build 42 & 41 - Persistent Mapped VBO & State Batching Governor.
 * 
 * Implements persistent buffer mapping (GL_ARB_buffer_storage / OpenGL 4.4+):
 * - Keeps vertex and index data permanently mapped in client memory (GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT).
 * - Completely eliminates per-frame driver buffer re-allocations, glBufferData re-specs, and stalling glMapBuffer calls.
 * - Uses a multi-slot ring buffer with lightweight GPU sync fences (glFenceSync) for zero-stall concurrent CPU writes & GPU draws.
 * - Graceful fallback: on macOS (OpenGL 2.1/4.1) or older GPUs without ARB_buffer_storage, seamlessly falls back to direct memory streaming.
 */
public final class PersistentVBOGovernor {

    private static volatile boolean active = true;
    private static volatile boolean supported = false;
    private static volatile boolean initialized = false;

    // Live Telemetry
    public static final AtomicLong persistentBuffersAllocated = new AtomicLong(0);
    public static final AtomicLong totalBytesMapped = new AtomicLong(0);
    public static final AtomicLong fencesCreated = new AtomicLong(0);
    public static final AtomicLong gpuSyncWaits = new AtomicLong(0);
    public static final AtomicLong gpuSyncWaitNs = new AtomicLong(0);

    // OpenGL Constants
    public static final int GL_ARRAY_BUFFER = 0x8892;
    public static final int GL_ELEMENT_ARRAY_BUFFER = 0x8893;
    public static final int GL_MAP_READ_BIT = 0x0001;
    public static final int GL_MAP_WRITE_BIT = 0x0002;
    public static final int GL_MAP_PERSISTENT_BIT = 0x0040;
    public static final int GL_MAP_COHERENT_BIT = 0x0080;
    public static final int GL_MAP_FLUSH_EXPLICIT_BIT = 0x0010;
    public static final int GL_SYNC_GPU_COMMANDS_COMPLETE = 0x9117;
    public static final int GL_ALREADY_SIGNALED = 0x911A;
    public static final int GL_TIMEOUT_EXPIRED = 0x911B;
    public static final int GL_CONDITION_SATISFIED = 0x911C;

    private static final int DEFAULT_RING_SLOTS = 4;
    private static final List<PersistentBuffer> activeBuffers = new ArrayList<>();

    // Reflection handles
    private static Method glGenBuffersMethod = null;
    private static Method glBindBufferMethod = null;
    private static Method glDeleteBuffersMethod = null;
    private static Method glBufferStorageMethod = null;
    private static Method glMapBufferRangeMethod = null;
    private static Method glUnmapBufferMethod = null;

    private static Method glFenceSyncMethod = null;
    private static Method glClientWaitSyncMethod = null;
    private static Method glDeleteSyncMethod = null;

    private PersistentVBOGovernor() {}

    public static boolean isActive() {
        return active && supported;
    }

    public static void setActive(boolean value) {
        active = value;
    }

    public static boolean isSupported() {
        if (!initialized) {
            initReflection();
        }
        return supported;
    }

    public static synchronized void initReflection() {
        if (initialized) return;
        initialized = true;

        try {
            Class<?> gl15 = Class.forName("org.lwjgl.opengl.GL15");
            glGenBuffersMethod = gl15.getMethod("glGenBuffers");
            glBindBufferMethod = gl15.getMethod("glBindBuffer", int.class, int.class);
            glDeleteBuffersMethod = gl15.getMethod("glDeleteBuffers", int.class);
            glUnmapBufferMethod = gl15.getMethod("glUnmapBuffer", int.class);

            Class<?> gl30 = Class.forName("org.lwjgl.opengl.GL30");
            glMapBufferRangeMethod = gl30.getMethod("glMapBufferRange", int.class, long.class, long.class, int.class);

            // Buffer Storage (GL44 or ARBBufferStorage)
            Class<?> bufferStorageClass = null;
            try {
                bufferStorageClass = Class.forName("org.lwjgl.opengl.GL44");
            } catch (Throwable t) {
                try {
                    bufferStorageClass = Class.forName("org.lwjgl.opengl.ARBBufferStorage");
                } catch (Throwable ignored) {}
            }

            if (bufferStorageClass != null) {
                try {
                    glBufferStorageMethod = bufferStorageClass.getMethod("glBufferStorage", int.class, long.class, int.class);
                } catch (NoSuchMethodException e) {
                    try {
                        glBufferStorageMethod = bufferStorageClass.getMethod("glBufferStorage", int.class, ByteBuffer.class, int.class);
                    } catch (Throwable ignored) {}
                }
            }

            // Sync Fences (GL32 or ARBSync)
            Class<?> syncClass = null;
            try {
                syncClass = Class.forName("org.lwjgl.opengl.GL32");
            } catch (Throwable t) {
                try {
                    syncClass = Class.forName("org.lwjgl.opengl.ARBSync");
                } catch (Throwable ignored) {}
            }

            if (syncClass != null) {
                glFenceSyncMethod = syncClass.getMethod("glFenceSync", int.class, int.class);
                glClientWaitSyncMethod = syncClass.getMethod("glClientWaitSync", long.class, int.class, long.class);
                glDeleteSyncMethod = syncClass.getMethod("glDeleteSync", long.class);
            }

            if (glBufferStorageMethod != null && glMapBufferRangeMethod != null && glFenceSyncMethod != null) {
                supported = true;
                PZOLogger.success("[PersistentVBOGovernor] ARB_buffer_storage Persistent VBO Pipeline initialized (Zero-Copy GPU Memory Mapping)");
            } else {
                supported = false;
                PZOLogger.info("[PersistentVBOGovernor] Persistent VBOs unsupported on host driver/OS (Graceful fallback to direct streaming)");
            }
        } catch (Throwable t) {
            supported = false;
            PZOLogger.info("[PersistentVBOGovernor] Notice on OpenGL ARB_buffer_storage discovery: " + t.getMessage());
        }
    }

    /**
     * Allocates a persistent mapped multi-slot ring buffer.
     * Returns null if persistent buffer storage is unsupported on the current platform/driver.
     */
    public static synchronized PersistentBuffer allocatePersistentBuffer(int target, int slotSizeBytes, int numSlots) {
        if (!isSupported() || !active) return null;

        try {
            int bufferId = ((Number) glGenBuffersMethod.invoke(null)).intValue();
            if (bufferId <= 0) return null;

            int totalSlots = Math.max(2, numSlots > 0 ? numSlots : DEFAULT_RING_SLOTS);
            long totalBytes = (long) slotSizeBytes * totalSlots;

            glBindBufferMethod.invoke(null, target, bufferId);

            int storageFlags = GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT;
            if (glBufferStorageMethod.getParameterTypes()[1] == long.class) {
                glBufferStorageMethod.invoke(null, target, totalBytes, storageFlags);
            } else {
                ByteBuffer dummy = ByteBuffer.allocateDirect((int) totalBytes);
                glBufferStorageMethod.invoke(null, target, dummy, storageFlags);
            }

            int mapFlags = GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT;
            ByteBuffer mappedBuffer = (ByteBuffer) glMapBufferRangeMethod.invoke(null, target, 0L, totalBytes, mapFlags);
            if (mappedBuffer == null) {
                glDeleteBuffersMethod.invoke(null, bufferId);
                return null;
            }

            mappedBuffer.order(ByteOrder.nativeOrder());
            PersistentBuffer pBuf = new PersistentBuffer(bufferId, target, slotSizeBytes, totalSlots, mappedBuffer);
            activeBuffers.add(pBuf);

            persistentBuffersAllocated.incrementAndGet();
            totalBytesMapped.addAndGet(totalBytes);

            return pBuf;
        } catch (Throwable t) {
            PZOLogger.warn("[PersistentVBOGovernor] Buffer allocation fallback: " + t.getMessage());
            return null;
        }
    }

    /**
     * Managed Persistent Buffer Instance.
     */
    public static final class PersistentBuffer {
        private final int bufferId;
        private final int target;
        private final int slotSizeBytes;
        private final int numSlots;
        private final ByteBuffer rawMappedBuffer;
        private final long[] slotFences;
        private int currentSlot = 0;
        private boolean disposed = false;

        private PersistentBuffer(int bufferId, int target, int slotSizeBytes, int numSlots, ByteBuffer rawMappedBuffer) {
            this.bufferId = bufferId;
            this.target = target;
            this.slotSizeBytes = slotSizeBytes;
            this.numSlots = numSlots;
            this.rawMappedBuffer = rawMappedBuffer;
            this.slotFences = new long[numSlots];
        }

        public int getBufferId() {
            return bufferId;
        }

        public int getTarget() {
            return target;
        }

        public int getSlotSizeBytes() {
            return slotSizeBytes;
        }

        public int getSlotOffsetBytes() {
            return currentSlot * slotSizeBytes;
        }

        /**
         * Advances to the next ring slot, waiting on any active GPU fence if the GPU is still reading it.
         * Returns the mapped ByteBuffer positioned at the start of the current slot.
         */
        public ByteBuffer acquireSlot() {
            if (disposed) return null;

            currentSlot = (currentSlot + 1) % numSlots;
            long fence = slotFences[currentSlot];
            if (fence != 0L) {
                try {
                    long startNs = System.nanoTime();
                    // 1 second timeout = 1_000_000_000L
                    int status = ((Number) glClientWaitSyncMethod.invoke(null, fence, 0, 1_000_000_000L)).intValue();
                    long elapsed = System.nanoTime() - startNs;
                    if (elapsed > 100_000L) {
                        gpuSyncWaits.incrementAndGet();
                        gpuSyncWaitNs.addAndGet(elapsed);
                    }
                    glDeleteSyncMethod.invoke(null, fence);
                    slotFences[currentSlot] = 0L;
                } catch (Throwable ignored) {}
            }

            int offset = currentSlot * slotSizeBytes;
            rawMappedBuffer.position(offset);
            rawMappedBuffer.limit(offset + slotSizeBytes);
            return rawMappedBuffer.slice().order(ByteOrder.nativeOrder());
        }

        /**
         * Places a GPU sync fence for the current slot after draw commands are issued.
         */
        public void markDrawn() {
            if (disposed) return;
            try {
                if (slotFences[currentSlot] != 0L) {
                    glDeleteSyncMethod.invoke(null, slotFences[currentSlot]);
                }
                slotFences[currentSlot] = ((Number) glFenceSyncMethod.invoke(null, GL_SYNC_GPU_COMMANDS_COMPLETE, 0)).longValue();
                fencesCreated.incrementAndGet();
            } catch (Throwable ignored) {}
        }

        public synchronized void dispose() {
            if (disposed) return;
            disposed = true;
            try {
                for (int i = 0; i < numSlots; i++) {
                    if (slotFences[i] != 0L) {
                        glDeleteSyncMethod.invoke(null, slotFences[i]);
                        slotFences[i] = 0L;
                    }
                }
                if (glUnmapBufferMethod != null) {
                    glBindBufferMethod.invoke(null, target, bufferId);
                    glUnmapBufferMethod.invoke(null, target);
                }
                if (glDeleteBuffersMethod != null) {
                    glDeleteBuffersMethod.invoke(null, bufferId);
                }
            } catch (Throwable ignored) {}
        }
    }

    public static synchronized void disposeAll() {
        for (PersistentBuffer b : activeBuffers) {
            b.dispose();
        }
        activeBuffers.clear();
    }

    public static String getTelemetrySummary() {
        return String.format("PersistentVBO: supported=%s | active=%s | buffers=%d | mapped_mb=%.1f | waits=%d",
                supported ? "true" : "false",
                isActive() ? "true" : "fallback",
                persistentBuffersAllocated.get(),
                totalBytesMapped.get() / (1024.0 * 1024.0),
                gpuSyncWaits.get());
    }
}
