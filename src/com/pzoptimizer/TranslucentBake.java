package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.FieldInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Makes a translucent-flagged sprite (IsoSprite.depthFlags bit 2) that FoliageLod moved into a chunk texture look the
 * way vanilla's per-frame translucent pass draws it.
 *
 * Two things go wrong when such a sprite is baked as is:
 *  - Depth. The tile depth shader writes depth for every pixel the sprite's depth texture covers, however transparent.
 *    A character drawn after the chunk texture then fails the depth test on the fence's faint pixels and comes out
 *    stippled. Here the sprite is baked in two passes: colour with depth writes off, then depth only (colour mask off)
 *    through the opaque-pixels shader, which keeps depth for texels above 0.8 alpha. Faint pixels no longer hide what
 *    stands behind them; solid bars and table tops still do.
 *  - Transparency over an empty part of the texture. Chunk textures hold premultiplied colour but reach the screen
 *    through a SRC_ALPHA blend, so a half-transparent pixel with nothing under it in the texture (water is drawn
 *    separately) is darkened twice and shows black. Sprites with water in the squares they cover stay per frame.
 *
 * Also per frame, as in vanilla: lit light fixtures, mirrors, glass, curtains and decals lying on the floor (puddles
 * are drawn over the chunk texture, and vanilla draws those decals after the puddles).
 */
public final class TranslucentBake {

    // Set by the agent once both patches are in; without them nothing translucent is baked
    static volatile boolean spriteHooked, cellHooked;
    public static final AtomicLong twoPassBakes = new AtomicLong();
    public static final AtomicLong keptPerFrame = new AtomicLong();

    private static final int PASS_NONE = 0, PASS_COLOUR = 1, PASS_DEPTH = 2;
    // Main thread only: the bake records its draws there
    private static int pass = PASS_NONE;
    private static final IdentityHashMap<Object, Boolean> spriteVerdicts = new IdentityHashMap<>();

    private static final class H {
        static final MethodHandle RENDER_MINUS_FLOOR = lookup();

        private static MethodHandle lookup() {
            try {
                Class<?> cell = Class.forName("zombie.iso.fboRenderChunk.FBORenderCell");
                return MethodHandles.privateLookupIn(cell, MethodHandles.lookup()).findVirtual(cell, "renderMinusFloor",
                    MethodType.methodType(void.class, zombie.iso.IsoObject.class))
                    .asType(MethodType.methodType(void.class, Object.class, Object.class));
            } catch (Throwable t) {
                PZOLogger.warn("[TranslucentBake] FBORenderCell.renderMinusFloor(IsoObject) not found, translucent sprites stay per frame: " + t);
                cellHooked = false;
                return null;
            }
        }
    }

    private TranslucentBake() {}

    static boolean ready() {
        return spriteHooked && cellHooked;
    }

    /** Whether a far object's translucent sprite may be baked; asked while its render layer is calculated. */
    static boolean canBake(Object object, Object sprite) {
        if (!ready()) return false;
        // A window stays per frame (glass), so a curtain baked under it would end up behind the glass
        if (object instanceof zombie.iso.objects.IsoCurtain) {
            keptPerFrame.incrementAndGet();
            return false;
        }
        Boolean ok = spriteVerdicts.get(sprite);
        if (ok == null) {
            ok = spriteAllowed((zombie.iso.sprite.IsoSprite) sprite);
            spriteVerdicts.put(sprite, ok);
        }
        if (ok && waterNearby((zombie.iso.IsoObject) object)) ok = false;
        if (!ok) keptPerFrame.incrementAndGet();
        return ok;
    }

    private static boolean spriteAllowed(zombie.iso.sprite.IsoSprite sprite) {
        zombie.core.properties.PropertyContainer p = sprite.getProperties();
        if (p == null) return true;
        if (p.has(zombie.iso.SpriteDetails.IsoFlagType.HasLightOnSprite)) return false;
        if (p.has(zombie.iso.SpriteDetails.IsoFlagType.attachedFloor) || p.has(zombie.iso.SpriteDetails.IsoFlagType.FloorOverlay)) return false;
        if (p.has("IsMirror") || p.has("GlassRemovedOffset")) return false;
        for (String key : new String[] {"Material", "Material2", "Material3", "MaterialType"}) {
            String v = p.get(key);
            if (v != null && v.toLowerCase(java.util.Locale.ROOT).contains("glass")) return false;
        }
        return true;
    }

    // A tall sprite covers its own square and the squares behind it on screen (north-west), up to three tiles back
    private static boolean waterNearby(zombie.iso.IsoObject object) {
        zombie.iso.IsoGridSquare sq = object.square;
        if (sq == null) return true;
        zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance.currentCell;
        if (cell == null) return true;
        return waterInArea(cell, sq.x, sq.y, sq.z) || (sq.z != 0 && waterInArea(cell, sq.x, sq.y, 0));
    }

    private static boolean waterInArea(zombie.iso.IsoCell cell, int sx, int sy, int z) {
        for (int y = sy - 3; y <= sy + 1; y++) {
            for (int x = sx - 3; x <= sx + 1; x++) {
                zombie.iso.IsoGridSquare s = cell.getGridSquare(x, y, z);
                if (s != null && s.has(zombie.iso.SpriteDetails.IsoFlagType.water)) return true;
            }
        }
        return false;
    }

    /** Replaces this.renderMinusFloor(object) in the bake's object loop. */
    public static void renderMinusFloor(Object cell, Object object) throws Throwable {
        zombie.iso.sprite.IsoSprite sprite = ((zombie.iso.IsoObject) object).getSprite();
        if (sprite == null || (sprite.depthFlags & 2) == 0 || !ready()) {
            H.RENDER_MINUS_FLOOR.invokeExact(cell, object);
            return;
        }
        // Vanilla never puts a translucent-flagged sprite in this layer; only FoliageLod's far classification does
        try {
            pass = PASS_COLOUR;
            H.RENDER_MINUS_FLOOR.invokeExact(cell, object);
            pass = PASS_DEPTH;
            zombie.IndieGL.glColorMask(false, false, false, false);
            H.RENDER_MINUS_FLOOR.invokeExact(cell, object);
            twoPassBakes.incrementAndGet();
        } finally {
            pass = PASS_NONE;
            zombie.IndieGL.glColorMask(true, true, true, true);
            zombie.IndieGL.glDepthMask(true);
        }
    }

    /** Replaces the depthFlags read that picks the shader in IsoSprite.startTileDepthShader. */
    public static int shaderFlags(Object sprite) {
        int flags = ((zombie.iso.sprite.IsoSprite) sprite).depthFlags;
        if (pass == PASS_NONE) return flags;
        // The draw paths set depth writes back on just before this, so it is switched off here
        if (pass == PASS_COLOUR) {
            zombie.IndieGL.glDepthMask(false);
            return flags;
        }
        return flags | 4;
    }

    /** Replaces the SceneShaderStore.tileDepthShader reads in IsoSprite.startTileDepthShader2 (walls, fences). */
    public static zombie.tileDepth.TileDepthShader wallShader() {
        if (pass == PASS_DEPTH && zombie.core.SceneShaderStore.opaqueDepthShader != null) {
            return zombie.core.SceneShaderStore.opaqueDepthShader;
        }
        return zombie.core.SceneShaderStore.tileDepthShader;
    }

    /** The first wallShader read of a call also switches depth writes off in the colour pass. */
    public static zombie.tileDepth.TileDepthShader wallShaderFirst() {
        if (pass == PASS_COLOUR) zombie.IndieGL.glDepthMask(false);
        return wallShader();
    }

    static boolean patchSprite(ClassNode node) {
        MethodNode start = AsmSupport.findMethod(node, "startTileDepthShader", "(Lzombie/iso/IsoObject;FFFFZ)V");
        MethodNode start2 = AsmSupport.findMethod(node, "startTileDepthShader2", "(IIFFFFZ)V");
        if (start == null || start2 == null) return false;
        int flagReads = 0;
        for (AbstractInsnNode insn = start.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD && "zombie/iso/sprite/IsoSprite".equals(f.owner)
                    && "depthFlags".equals(f.name) && "I".equals(f.desc)) {
                MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/TranslucentBake", "shaderFlags",
                    "(Ljava/lang/Object;)I", false);
                start.instructions.set(f, call);
                insn = call;
                flagReads++;
            }
        }
        if (flagReads != 1) return false;
        int shaderReads = 0;
        for (AbstractInsnNode insn = start2.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC && "zombie/core/SceneShaderStore".equals(f.owner)
                    && "tileDepthShader".equals(f.name)) {
                MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/TranslucentBake",
                    shaderReads == 0 ? "wallShaderFirst" : "wallShader", "()Lzombie/tileDepth/TileDepthShader;", false);
                start2.instructions.set(f, call);
                insn = call;
                shaderReads++;
            }
        }
        return shaderReads > 0;
    }

    static boolean patchRenderCell(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "renderMinusFloor",
            "(Lzombie/iso/IsoChunk;Lzombie/iso/IsoGridSquare;Ljava/util/List;)V");
        if (m == null) {
            m = AsmSupport.findMethod(node, "renderMinusFloor",
                "(Lzombie/iso/IsoChunk;Lzombie/iso/IsoGridSquare;Lzombie/util/list/PZArrayList;)V");
        }
        if (m == null) return false;
        int n = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && (mi.getOpcode() == Opcodes.INVOKEVIRTUAL || mi.getOpcode() == Opcodes.INVOKESPECIAL)
                    && node.name.equals(mi.owner) && "renderMinusFloor".equals(mi.name) && "(Lzombie/iso/IsoObject;)V".equals(mi.desc)) {
                MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/TranslucentBake", "renderMinusFloor",
                    "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                m.instructions.set(mi, call);
                insn = call;
                n++;
            }
        }
        return n == 1;
    }
}
