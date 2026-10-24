package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Distant wind-swayed foliage is baked into the chunk texture instead of being drawn every frame.
 *
 * With the wind sprite option on, FBORenderCell classifies every tree and every object with wind render effects as
 * Translucent, so each one is drawn individually every frame in the translucent pass even when it is far away and
 * its sway is a pixel or two. Here the loaded chunks are split each frame into near and far by distance from the
 * player (with hysteresis, so a chunk does not flip back and forth at the edge). While an object of a far chunk is
 * classified, the two wind checks in isObjectRenderLayer_MinusFloor / _Translucent answer "no wind", so the object
 * lands in the layer it would get with wind off: baked, unless something else (a translucent sprite, animation,
 * fire, a fade, other render effects) keeps it in the per-frame pass. When a chunk changes side it is invalidated
 * with DIRTY_TREES, as vanilla does when the wind option is toggled, so it is re-baked with the new answer and near
 * foliage sways again.
 *
 *
 * The same applies to sprites flagged translucent (IsoSprite.depthFlags bit 2): chain-link fencing, scaffolding,
 * street decorations, ornamental plants. Vanilla draws every one of them in the per-frame pass, wind or no wind;
 * on the benchmark route they were 94% of what that pass draws, ~350 a frame. For objects of a far chunk the
 * classifier sees the bit cleared, so those bake too, drawn by TranslucentBake so they keep vanilla's depth and
 * transparency; sprites it cannot bake faithfully stay per frame.
 *
 * Idea from fmac (Atmospherics), who found translucent sprites to be disproportionately expensive and caches distant
 * ones. Atmospherics ships its own version of this, so this one stands down while that mod is active.
 */
public final class FoliageLod {

    public static volatile boolean active = true;
    public static volatile boolean spritesActive = true;
    public static final AtomicLong spritesBaked = new AtomicLong();
    public static volatile float radiusTiles = 24.0f;
    private static final float HYSTERESIS_TILES = 8.0f;
    private static final int CHUNK_TILES = 8;
    private static final int CHECK_MODS_EVERY = 300;

    public static final AtomicLong chunkFlips = new AtomicLong();
    public static final AtomicLong objectsBaked = new AtomicLong();

    // Game thread only. Chunk world coordinates -> far; entries outside the loaded grid are dropped each frame
    private static Map<Long, Boolean> farByChunk = new HashMap<>();
    private static Map<Long, Boolean> nextFar = new HashMap<>();
    private static boolean classifyingFar;
    private static Object classifyingObject;
    private static boolean otherModOwnsIt;
    private static int framesUntilModCheck;

    private FoliageLod() {}

    // Multiplied by an odd constant: still one key per chunk, but neighbouring chunks no longer share HashMap buckets
    private static long key(int wx, int wy) {
        return (((long) wx << 32) ^ (wy & 0xffffffffL)) * 0x2E41CBF68F940BCFL;
    }

    private static boolean enabled() {
        return active && !otherModOwnsIt;
    }

    // Prefix of FBORenderCell.checkWindEffectsOption(playerIndex), once per frame per player
    public static void update(int playerIndex) {
        if (playerIndex != 0) return;
        if (--framesUntilModCheck <= 0) {
            framesUntilModCheck = CHECK_MODS_EVERY;
            boolean atmospherics = false;
            try {
                atmospherics = zombie.ZomboidFileSystem.instance.getModIDs().contains("Atmospherics");
            } catch (Throwable ignored) {
            }
            if (atmospherics != otherModOwnsIt) {
                otherModOwnsIt = atmospherics;
                if (atmospherics) {
                    PZOLogger.info("[FoliageLod] Atmospherics is active and caches distant foliage itself; standing down");
                }
                resetAll();
            }
        }
        if (!enabled() || (!spritesActive && !zombie.core.Core.getInstance().getOptionDoWindSpriteEffects())) {
            if (!farByChunk.isEmpty()) resetAll();
            return;
        }
        zombie.characters.IsoPlayer player = zombie.characters.IsoPlayer.players[0];
        if (player == null || zombie.iso.IsoWorld.instance.currentCell == null) return;
        zombie.iso.IsoChunkMap map = zombie.iso.IsoWorld.instance.currentCell.getChunkMap(0);
        if (map == null) return;
        // Distance from what the camera follows (Viewpoint and spectating move it off the player)
        float px = zombie.iso.IsoCamera.frameState.camCharacterX, py = zombie.iso.IsoCamera.frameState.camCharacterY;
        if (px == 0.0f && py == 0.0f) {
            px = player.getX();
            py = player.getY();
        }
        float r = radiusTiles, h = HYSTERESIS_TILES * 0.5f;
        Map<Long, Boolean> seen = nextFar;
        seen.clear();
        int n = zombie.iso.IsoChunkMap.chunkGridWidth;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                zombie.iso.IsoChunk c = map.getChunk(x, y);
                if (c == null) continue;
                float dx = c.wx * CHUNK_TILES + CHUNK_TILES * 0.5f - px, dy = c.wy * CHUNK_TILES + CHUNK_TILES * 0.5f - py;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                long k = key(c.wx, c.wy);
                Boolean prev = farByChunk.get(k);
                boolean far = prev == null ? d > r : (prev ? d > r - h : d > r + h);
                if (prev != null && prev != far) {
                    // Re-bake with the new answer, the way vanilla reacts to the wind option itself changing
                    c.invalidateRenderChunkLevels(zombie.iso.fboRenderChunk.FBORenderChunk.DIRTY_TREES);
                    chunkFlips.incrementAndGet();
                }
                seen.put(k, far);
            }
        }
        nextFar = farByChunk;
        farByChunk = seen;
    }

    // Drops every far chunk back to near and re-bakes it, e.g. when Atmospherics takes over or wind is switched off
    private static void resetAll() {
        if (farByChunk.isEmpty()) return;
        try {
            zombie.iso.IsoChunkMap map = zombie.iso.IsoWorld.instance.currentCell == null ? null
                : zombie.iso.IsoWorld.instance.currentCell.getChunkMap(0);
            if (map != null) {
                int n = zombie.iso.IsoChunkMap.chunkGridWidth;
                for (int y = 0; y < n; y++) {
                    for (int x = 0; x < n; x++) {
                        zombie.iso.IsoChunk c = map.getChunk(x, y);
                        if (c != null && Boolean.TRUE.equals(farByChunk.get(key(c.wx, c.wy)))) {
                            c.invalidateRenderChunkLevels(zombie.iso.fboRenderChunk.FBORenderChunk.DIRTY_TREES);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        farByChunk.clear();
    }

    // Prefix of FBORenderCell.calculateObjectRenderLayer(object)
    public static void classify(Object object) {
        classifyingFar = false;
        classifyingObject = object;
        if (!enabled()) return;
        zombie.iso.IsoGridSquare sq = ((zombie.iso.IsoObject) object).square;
        zombie.iso.IsoChunk c = sq == null ? null : sq.chunk;
        if (c == null) return;
        Boolean far = farByChunk.get(key(c.wx, c.wy));
        classifyingFar = far != null && far;
    }

    // Replaces Core.getOptionDoWindSpriteEffects() inside the two layer tests
    public static boolean windOption(Object core) {
        if (classifyingFar) {
            objectsBaked.incrementAndGet();
            return false;
        }
        return ((zombie.core.Core) core).getOptionDoWindSpriteEffects();
    }

    // Replaces the IsoSprite.depthFlags reads of the classifier: a far object's sprite is not treated as translucent
    public static int depthFlags(Object sprite) {
        int flags = ((zombie.iso.sprite.IsoSprite) sprite).depthFlags;
        if (classifyingFar && spritesActive && (flags & 2) != 0 && TranslucentBake.canBake(classifyingObject, sprite)) {
            spritesBaked.incrementAndGet();
            return flags & ~2;
        }
        return flags;
    }

    // Replaces IsoObject.getWindRenderEffects() inside the two layer tests
    public static Object windEffects(Object object) {
        return classifyingFar ? null : ((zombie.iso.IsoObject) object).getWindRenderEffects();
    }

    static boolean patchRenderCell(ClassNode node) {
        MethodNode classify = AsmSupport.findMethod(node, "calculateObjectRenderLayer",
            "(Lzombie/iso/IsoObject;)Lzombie/iso/fboRenderChunk/ObjectRenderLayer;");
        MethodNode check = AsmSupport.findMethod(node, "checkWindEffectsOption", "(I)V");
        MethodNode minus = AsmSupport.findMethod(node, "isObjectRenderLayer_MinusFloor", "(Lzombie/iso/IsoObject;)Z");
        MethodNode translucent = AsmSupport.findMethod(node, "isObjectRenderLayer_Translucent", "(Lzombie/iso/IsoObject;)Z");
        if (classify == null || check == null || minus == null || translucent == null) return false;
        int redirected = 0;
        for (MethodNode m : new MethodNode[] {minus, translucent}) {
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode mi) || mi.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
                if ("zombie/core/Core".equals(mi.owner) && "getOptionDoWindSpriteEffects".equals(mi.name) && "()Z".equals(mi.desc)) {
                    m.instructions.set(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/FoliageLod", "windOption",
                        "(Ljava/lang/Object;)Z", false));
                    redirected++;
                    break;
                }
            }
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode mi) || mi.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
                if ("getWindRenderEffects".equals(mi.name) && "()Lzombie/iso/objects/ObjectRenderEffects;".equals(mi.desc)) {
                    MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/FoliageLod", "windEffects",
                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                    m.instructions.set(mi, call);
                    m.instructions.insert(call, new com.pzoptimizer.shaded.asm.tree.TypeInsnNode(Opcodes.CHECKCAST,
                        "zombie/iso/objects/ObjectRenderEffects"));
                    redirected++;
                    break;
                }
            }
        }
        if (redirected != 4) return false;
        int depthReads = 0;
        for (MethodNode m : new MethodNode[] {classify, minus, translucent}) {
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof com.pzoptimizer.shaded.asm.tree.FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD
                        && "zombie/iso/sprite/IsoSprite".equals(f.owner) && "depthFlags".equals(f.name) && "I".equals(f.desc)) {
                    MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/FoliageLod", "depthFlags",
                        "(Ljava/lang/Object;)I", false);
                    m.instructions.set(f, call);
                    insn = call;
                    depthReads++;
                }
            }
        }
        if (depthReads != 3) return false;
        com.pzoptimizer.shaded.asm.tree.InsnList pre = new com.pzoptimizer.shaded.asm.tree.InsnList();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/FoliageLod", "classify", "(Ljava/lang/Object;)V", false));
        classify.instructions.insert(pre);
        com.pzoptimizer.shaded.asm.tree.InsnList upd = new com.pzoptimizer.shaded.asm.tree.InsnList();
        upd.add(new VarInsnNode(Opcodes.ILOAD, 1));
        upd.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/FoliageLod", "update", "(I)V", false));
        check.instructions.insert(upd);
        return true;
    }
}
