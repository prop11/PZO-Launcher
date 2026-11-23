package com.pzoptimizer.pzoe;

import com.pzoptimizer.AsmSupport;
import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.util.IdentityHashMap;
import zombie.core.textures.ColorInfo;
import zombie.iso.IsoGridSquare;
import zombie.iso.objects.IsoTree;
import zombie.iso.sprite.IsoSprite;

/**
 * Autumn Colours (PZO Enhancements): in autumn each broadleaf tree turns its own way. The game picks one autumn
 * picture per kind of tree, so a street of maples is all the same orange; here each tree's tint is nudged by a hash
 * of its square, so some go red, some golden, a few lag behind still green. Evergreens stay as they are.
 *
 * One call at the start of IsoTree.renderInner swaps the colour it draws with (a scratch copy, the game's own
 * ColorInfo is never written). Only multiplies channels, never above 1, so lighting and the night stay right.
 * No shader: the card sends on/off and strength (modApi "autumnTrees").
 */
public final class AutumnTrees {

    static final String TREE = "zombie/iso/objects/IsoTree";

    static volatile boolean on;
    static volatile float amount = 1f;

    // Main thread only (trees render there). A ring, not one copy, in case a draw holds on to the colour a moment.
    private static final ColorInfo[] scratch = new ColorInfo[256];
    private static int next;
    private static final IdentityHashMap<IsoSprite, Boolean> evergreen = new IdentityHashMap<>();
    private static float season;
    private static long seasonCheckedMs;

    // How far into autumn, by month (0-based): September starts, October is the peak, November falls bare
    private static final float[] MONTH = {0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.45f, 1f, 0.55f, 0f};

    // Red, golden, still green, as the game drew it
    private static final float[][] TINTS = {
        {1f, 0.78f, 0.68f}, {1f, 0.92f, 0.62f}, {0.86f, 1f, 0.84f}, {1f, 1f, 1f}};

    private AutumnTrees() {}

    /** Start of IsoTree.renderInner: the colour to draw this tree with. */
    public static ColorInfo tint(IsoTree tree, ColorInfo col) {
        if (!on || col == null || tree == null) return col;
        try {
            long now = System.currentTimeMillis();
            if (now - seasonCheckedMs > 2000) {
                seasonCheckedMs = now;
                zombie.GameTime gt = zombie.GameTime.getInstance();
                season = gt == null ? 0f : MONTH[Math.floorMod(gt.getMonth(), 12)];
            }
            float k = season * amount;
            IsoGridSquare sq = tree.getSquare();
            if (k <= 0.01f || sq == null || isEvergreen(tree.getSprite())) return col;
            int h = sq.getX() * 73856093 ^ sq.getY() * 19349663;
            h ^= h >>> 13;
            h *= 0x5bd1e995;
            h ^= h >>> 15;
            int pick = Math.floorMod(h, 100);
            float[] t = pick < 32 ? TINTS[0] : pick < 64 ? TINTS[1] : pick < 82 ? TINTS[2] : TINTS[3];
            // A little spread inside each group too
            float jitter = 0.92f + 0.08f * (Math.floorMod(h >>> 8, 64) / 63f);
            k = Math.min(1f, k);
            ColorInfo out = scratch[next];
            if (out == null) out = scratch[next] = new ColorInfo();
            next = (next + 1) & 255;
            out.set(col.r * (1f + (t[0] - 1f) * k), col.g * (1f + (t[1] * jitter - 1f) * k), col.b * (1f + (t[2] - 1f) * k), col.a);
            return out;
        } catch (Throwable e) {
            return col;
        }
    }

    private static boolean isEvergreen(IsoSprite s) {
        if (s == null) return true;
        Boolean b = evergreen.get(s);
        if (b == null) {
            String n = s.getName() == null ? "" : s.getName().toLowerCase(java.util.Locale.ROOT);
            b = n.contains("pine") || n.contains("hemlock") || n.contains("holly") || n.contains("spruce")
                || n.contains("cedar") || n.contains("fir_") || n.contains("juniper");
            evergreen.put(s, b);
        }
        return b;
    }

    static boolean patch(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "renderInner", "(FFFLzombie/core/textures/ColorInfo;ZZ)V");
        if (m == null) return false;
        InsnList l = new InsnList();
        l.add(new VarInsnNode(Opcodes.ALOAD, 0));
        l.add(new VarInsnNode(Opcodes.ALOAD, 4));
        l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/pzoe/AutumnTrees", "tint",
            "(Lzombie/iso/objects/IsoTree;Lzombie/core/textures/ColorInfo;)Lzombie/core/textures/ColorInfo;", false));
        l.add(new VarInsnNode(Opcodes.ASTORE, 4));
        m.instructions.insert(l);
        return true;
    }
}
