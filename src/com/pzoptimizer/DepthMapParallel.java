package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.InsnNode;
import com.pzoptimizer.shaded.asm.tree.JumpInsnNode;
import com.pzoptimizer.shaded.asm.tree.LabelNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;
import zombie.core.logger.ExceptionLogger;
import zombie.tileDepth.TileDepthTextures;
import zombie.tileDepth.TilesetDepthTexture;

/**
 * Loads depth maps in parallel (JVM_DepthMapParallel).
 *
 * Every media/depthmaps/DEPTH_*.png (~220 for the game, more with tile mods) gets its own file task, but
 * TileDepthTextures.LoadTask.call does the whole PNG decode while holding the TileDepthTextures lock, so the workers
 * just take turns. Now the tileset is built outside the lock and only looked up and stored under it. Same code
 * (TilesetDepthTexture.load), same file, same row count.
 */
public final class DepthMapParallel {

    public static volatile boolean active = PZOConfig.isDepthMapParallelEnabled();
    public static final AtomicLong tilesets = new AtomicLong();

    private static Field taskTextures, taskPath, texturesTilesets, texturesRows;
    private static volatile boolean broken;

    private DepthMapParallel() {}

    private static synchronized boolean init(Object task) {
        if (taskTextures != null) return true;
        try {
            Class<?> tc = task.getClass();
            Field t = tc.getDeclaredField("textures");
            Field p = tc.getDeclaredField("path");
            Field ts = TileDepthTextures.class.getDeclaredField("tilesets");
            Field rows = TileDepthTextures.class.getDeclaredField("tilesetRows");
            t.setAccessible(true);
            p.setAccessible(true);
            ts.setAccessible(true);
            rows.setAccessible(true);
            taskPath = p;
            texturesTilesets = ts;
            texturesRows = rows;
            taskTextures = t;
            return true;
        } catch (Throwable e) {
            PZOLogger.warn("[DepthMapParallel] Game code changed, depth maps load as usual: " + e);
            broken = true;
            return false;
        }
    }

    /** Prepended to LoadTask.call(); true means I handled it and call() returns null like vanilla. */
    @SuppressWarnings("unchecked")
    public static boolean call(Object task) {
        if (!active || broken || !init(task)) return false;
        TileDepthTextures textures;
        String name;
        int rows;
        try {
            textures = (TileDepthTextures) taskTextures.get(task);
            String fileName = ((Path) taskPath.get(task)).toFile().getName();
            name = fileName.replaceFirst("DEPTH_", "").replace(".png", "");
            synchronized (textures) {
                if (((HashMap<String, TilesetDepthTexture>) texturesTilesets.get(textures)).get(name) != null) return true;
                // Same row count createTileset(name, true) would use, cached when the task was queued
                rows = ((HashMap<String, Integer>) texturesRows.get(textures)).getOrDefault(name, 0);
            }
        } catch (Throwable e) {
            PZOLogger.warn("[DepthMapParallel] " + e + ", depth maps load as usual");
            broken = true;
            return false;
        }
        if (rows == 0) return true;

        TilesetDepthTexture tileset = new TilesetDepthTexture(textures, name, 8, rows, true);
        if (tileset.fileExists()) {
            try {
                tileset.load();
            } catch (Exception ex) {
                ExceptionLogger.logException(ex);
            }
        }
        try {
            synchronized (textures) {
                HashMap<String, TilesetDepthTexture> map = (HashMap<String, TilesetDepthTexture>) texturesTilesets.get(textures);
                if (map.get(name) == null) map.put(name, tileset);
            }
        } catch (Throwable e) {
            PZOLogger.warn("[DepthMapParallel] " + e);
        }
        tilesets.incrementAndGet();
        return true;
    }

    public static void logStats() {
        PZOLogger.info("[DepthMapParallel] " + tilesets.get() + " depth map tilesets loaded in parallel");
    }

    static boolean patchLoadTask(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "call", "()Ljava/lang/Object;");
        if (m == null) return false;
        LabelNode go = new LabelNode();
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/DepthMapParallel", "call", "(Ljava/lang/Object;)Z", false));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, go));
        pre.add(new InsnNode(Opcodes.ACONST_NULL));
        pre.add(new InsnNode(Opcodes.ARETURN));
        pre.add(go);
        m.instructions.insert(pre);
        return true;
    }
}
