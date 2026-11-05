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
 * Bakes distant wind-swayed foliage into the chunk texture instead of drawing it every frame.
 *
 * With wind sprites on, FBORenderCell treats every tree and every object with wind effects as translucent, so each one
 * is drawn on its own every frame even far away where it sways a pixel or two. Each frame I split the loaded chunks
 * into near and far (with hysteresis so the edge doesn't flicker). For objects in far chunks the two wind checks in
 * isObjectRenderLayer_MinusFloor / _Translucent answer "no wind", so they land where they would with wind off: baked,
 * unless something else (translucent sprite, animation, fire, fade, other effects) keeps them per frame. When a chunk
 * switches side it gets DIRTY_TREES, same as vanilla does when you toggle the wind option, so it re-bakes and near
 * foliage sways again.
 *
 * Same for sprites flagged translucent (IsoSprite.depthFlags bit 2): chain-link fences, scaffolding, street
 * decorations, ornamental plants. Vanilla draws all of them per frame, wind or not; on the bench route they were 94% of
 * that pass (~350 a frame). The classifier sees the bit cleared so they bake too, through TranslucentBake so depth and
 * transparency match vanilla; in far chunks always, and in near chunks as well with JVM_TranslucentNearBake, since
 * nothing about them sways (on a 2026-11-05 bench 91% of the per-frame pass was still these, all near). Sprites it
 * can't bake faithfully stay per frame.
 *
 * Idea from fmac (Atmospherics), who found translucent sprites disproportionately expensive and caches distant ones.
 * Atmospherics has its own version, so this stands down while that mod is active.
 */
public final class FoliageLod {

    public static volatile boolean active = true;
    public static volatile boolean spritesActive = true;
    // Translucent-flagged sprites bake in near chunks too, not only far ones
    public static volatile boolean nearSprites = true;
    public static final AtomicLong spritesBaked = new AtomicLong();
    public static volatile float radiusTiles = 24.0f;
    private static final float HYSTERESIS_TILES = 8.0f;
    private static final int CHUNK_TILES = 8;
    private static final int CHECK_MODS_EVERY = 300;

    public static final AtomicLong chunkFlips = new AtomicLong();
    public static final AtomicLong objectsBaked = new AtomicLong();

    // Game thread only. Chunk world coords -> far; anything outside the loaded grid is dropped each frame
    private static Map<Long, Boolean> farByChunk = new HashMap<>();
    private static Map<Long, Boolean> nextFar = new HashMap<>();
    private static boolean classifyingFar;
    private static Object classifyingObject;
    private static boolean otherModOwnsIt;
    private static int framesUntilModCheck;

    private FoliageLod() {}

    // Times an odd constant: still one key per chunk, but neighbouring chunks stop landing in the same HashMap bucket
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
                atmospherics = GameCompat.modIds().contains("Atmospherics");
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
        // Distance from whatever the camera follows (Viewpoint and spectating move it off the player)
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
                    // Re-bake with the new answer, same as vanilla does when the wind option itself changes
                    c.invalidateRenderChunkLevels(zombie.iso.fboRenderChunk.FBORenderChunk.DIRTY_TREES);
                    chunkFlips.incrementAndGet();
                }
                seen.put(k, far);
            }
        }
        nextFar = farByChunk;
        farByChunk = seen;
    }

    /** JVM_TranslucentNearBake: every loaded chunk re-bakes with the new answer. */
    public static void setNearSprites(boolean value) {
        if (nearSprites == value) return;
        nearSprites = value;
        try {
            zombie.iso.IsoChunkMap map = zombie.iso.IsoWorld.instance == null || zombie.iso.IsoWorld.instance.currentCell == null ? null
                : zombie.iso.IsoWorld.instance.currentCell.getChunkMap(0);
            if (map == null) return;
            int n = zombie.iso.IsoChunkMap.chunkGridWidth;
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    zombie.iso.IsoChunk c = map.getChunk(x, y);
                    if (c != null) c.invalidateRenderChunkLevels(zombie.iso.fboRenderChunk.FBORenderChunk.DIRTY_TREES);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // Puts every far chunk back to near and re-bakes it, e.g. when Atmospherics takes over or wind gets switched off
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

    // Replaces the IsoSprite.depthFlags reads in the classifier, so a far object's sprite doesn't count as translucent
    public static int depthFlags(Object sprite) {
        int flags = ((zombie.iso.sprite.IsoSprite) sprite).depthFlags;
        boolean eligible = classifyingFar || (nearSprites && classifyingObject != null && enabled());
        if (eligible && spritesActive && (flags & 2) != 0 && TranslucentBake.canBake(classifyingObject, sprite)) {
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
