package com.pzoptimizer.pzoe.water;

import com.pzoptimizer.AsmSupport;
import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.util.WeakHashMap;
import zombie.GameTime;
import zombie.WorldSoundManager;
import zombie.characters.IsoPlayer;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.Clothing;
import zombie.network.GameClient;
import zombie.network.GameServer;

public final class WaterWading {
    public static final String TARGET = "zombie/characters/IsoPlayer";
    private static final WeakHashMap<IsoPlayer, WadeSample> samples = new WeakHashMap<>();

    private record WadeSample(long time, float x, float y) {}

    private WaterWading() {}

    public static float depth(IsoPlayer player) {
        if (!WaterGameplay.enabled("PZOEWater.Wading") || player.isDead() || player.isGhostMode() || player.isNoClip()
                || player.isAnimal() || player.getVehicle() != null || player.getZ() >= 0.5f) return 0f;
        var square = player.getCurrentSquare();
        if (WaterSwimming.openWater(square)) return 0f;
        float depth = WaterSurface.depthAt(square, player.getX(), player.getY());
        return depth >= 0.45f ? depth : 0f;
    }

    public static float movement(float vanilla, IsoPlayer player) {
        return WaterSwimming.movement(vanilla * (1f - 0.35f * depth(player)), player);
    }

    public static void update(IsoPlayer player) {
        WaterSwimming.update(player);
        if (GameClient.client || (!GameServer.server && !player.isLocalPlayer()) || GameTime.isGamePaused()) return;
        float depth = depth(player);
        if (depth <= 0f) {
            if (!samples.isEmpty()) samples.remove(player);
            return;
        }
        long now = System.nanoTime();
        WadeSample previous = samples.get(player);
        if (previous != null && now - previous.time() < 400_000_000L) return;
        float x = player.getX(), y = player.getY();
        samples.put(player, new WadeSample(now, x, y));
        if (previous == null || now - previous.time() > 2_000_000_000L) return;

        float dt = Math.min(0.8f, (now - previous.time()) * 1e-9f);
        wet(player.getClothingItem_Feet(), depth * dt * 18f);
        wet(player.getClothingItem_Legs(), Math.max(0f, depth - 0.6f) * dt * 15f);
        float dx = x - previous.x(), dy = y - previous.y();
        float moved = dx * dx + dy * dy;
        if (moved > 0.04f && moved < 6.25f && WorldSoundManager.instance != null) {
            int radius = 3 + Math.round(depth * 4f);
            WorldSoundManager.instance.addSound(player, (int) Math.floor(x), (int) Math.floor(y), 0, radius, radius);
        }
    }

    private static void wet(InventoryItem item, float amount) {
        if (amount > 0f && item instanceof Clothing clothing) {
            clothing.setWetness(Math.min(100f, clothing.getWetness() + amount));
        }
    }

    public static boolean patch(ClassNode node) {
        var method = AsmSupport.findMethod(node, "getGlobalMovementMod", "(Z)F");
        var update = AsmSupport.findMethod(node, "update", "()V");
        if (method == null || update == null) return false;
        int returns = 0;
        for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.FRETURN) continue;
            InsnList call = new InsnList();
            call.add(new VarInsnNode(Opcodes.ALOAD, 0));
            call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/pzoe/water/WaterWading", "movement",
                "(FLzombie/characters/IsoPlayer;)F", false));
            method.instructions.insertBefore(insn, call);
            returns++;
        }
        if (returns == 0) return false;
        AsmSupport.prependCall(update, "com/pzoptimizer/pzoe/water/WaterWading", "update", "(Lzombie/characters/IsoPlayer;)V");
        return true;
    }
}
