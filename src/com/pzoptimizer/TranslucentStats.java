package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.util.HashMap;
import java.util.Map;

/**
 * Benchmark diagnostic: what the per-frame translucent pass actually draws. Each FBORenderCell.renderTranslucent call
 * tallies the objects it is about to draw by the reason vanilla puts them in that pass (the checks of
 * isObjectRenderLayer_Translucent / calculateObjectRenderInfo, in their order), plus the sprite names, so the
 * results show which kind of object is worth caching. Only active while a benchmark runs.
 */
public final class TranslucentStats {

    public static volatile boolean active = false;

    // Game thread only
    private static final Map<String, long[]> byReason = new HashMap<>();
    private static final Map<String, long[]> bySprite = new HashMap<>();

    private TranslucentStats() {}

    // Prefix of renderTranslucent(square, layer)
    public static void square(Object squareObj, Object layer) {
        if (!active || squareObj == null) return;
        zombie.iso.IsoGridSquare square = (zombie.iso.IsoGridSquare) squareObj;
        int playerIndex = zombie.iso.IsoCamera.frameState.playerIndex;
        boolean wind = zombie.core.Core.getInstance().getOptionDoWindSpriteEffects();
        java.util.List<zombie.iso.IsoObject> objects = square.getObjects();
        int n = objects.size();
        for (int i = 0; i < n; i++) {
            zombie.iso.IsoObject o = objects.get(i);
            zombie.iso.fboRenderChunk.ObjectRenderInfo info = o.getRenderInfo(playerIndex);
            if (info.layer != layer) continue;
            String reason = reason(o, info, wind);
            byReason.computeIfAbsent(reason, k -> new long[1])[0]++;
            zombie.iso.sprite.IsoSprite s = o.getSprite();
            String name = s == null || s.name == null ? "(no sprite)" : s.name;
            bySprite.computeIfAbsent(reason + " | " + name, k -> new long[1])[0]++;
        }
    }

    private static String reason(zombie.iso.IsoObject o, zombie.iso.fboRenderChunk.ObjectRenderInfo info, boolean wind) {
        zombie.iso.sprite.IsoSprite s = o.getSprite();
        if (s != null && (s.depthFlags & 2) != 0) return "translucent sprite";
        if (o instanceof zombie.iso.objects.IsoFire) return "fire";
        if (o.isAnimating()) return "animating";
        if (wind && o instanceof zombie.iso.objects.IsoTree) return "tree (wind on)";
        if (wind && o.getWindRenderEffects() != null) return "wind effects";
        if (o instanceof zombie.iso.objects.IsoTree t) return t.fadeAlpha < 1.0f ? "tree fading" : "tree (see-through/peek)";
        if (o.getObjectRenderEffectsToApply() != null) return "render effects";
        if (info.targetAlpha < 1.0f) return "faded (cutaway/see-through)";
        if (o instanceof zombie.iso.objects.IsoMannequin) return "mannequin";
        return "other";
    }

    private static String top(Map<String, long[]> m, int n) {
        StringBuilder sb = new StringBuilder("{");
        m.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0])).limit(n)
            .forEach(e -> sb.append(sb.length() > 1 ? ",\n    " : "\n    ").append('"').append(e.getKey().replace("\"", "'"))
                .append("\": ").append(e.getValue()[0]));
        return sb.append(sb.length() > 1 ? "\n  }" : "}").toString();
    }

    public static String reasonsJson() {
        return top(byReason, 20);
    }

    public static String spritesJson() {
        return top(bySprite, 30);
    }

    static boolean patchRenderCell(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "renderTranslucent",
            "(Lzombie/iso/IsoGridSquare;Lzombie/iso/fboRenderChunk/ObjectRenderLayer;)V");
        if (m == null) return false;
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pre.add(new VarInsnNode(Opcodes.ALOAD, 2));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/TranslucentStats", "square",
            "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
        m.instructions.insert(pre);
        return true;
    }
}
