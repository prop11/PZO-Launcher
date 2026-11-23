package com.pzoptimizer.pzoe.water;

import com.pzoptimizer.AsmSupport;
import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.FieldInsnNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.InsnNode;
import com.pzoptimizer.shaded.asm.tree.JumpInsnNode;
import com.pzoptimizer.shaded.asm.tree.LabelNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.util.WeakHashMap;
import org.joml.Vector2f;
import zombie.GameTime;
import zombie.characters.CharacterStat;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.areas.SafeHouse;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.PacketTypes;
import zombie.network.packets.INetworkPacket;
import zombie.pathfind.PolygonalMap2;

public final class WaterSwimming {
    public static final String MOVING_OBJECT = "zombie/iso/IsoMovingObject";
    private static final String OWNER = "com/pzoptimizer/pzoe/water/WaterSwimming";
    private static final String OPTION = "PZOEWater.Swimming";
    private static final int DROWN_DELAY = 20;
    private static final WeakHashMap<IsoPlayer, SwimSample> swimmers = new WeakHashMap<>();

    private record SwimSample(long time, float endurance, float exhaustedSeconds) {}

    private WaterSwimming() {}

    private static boolean canSwim(IsoPlayer player) {
        return !player.isDead() && !player.isAnimal() && !player.isGhostMode() && !player.isNoClip()
            && player.getVehicle() == null && player.getZ() >= 0f && player.getZ() < 0.5f;
    }

    public static boolean openWater(IsoGridSquare square) {
        return square != null && square.z == 0 && square.has(IsoFlagType.water) && !square.hasFloorOverWater();
    }

    private static boolean passable(IsoGridSquare square) {
        return square != null && square.z == 0 && !square.isSolid()
            && (openWater(square) || !square.isSolidTrans() && square.TreatAsSolidFloor())
            && !square.isVehicleIntersecting();
    }

    private static boolean clearEdge(IsoGridSquare from, IsoGridSquare to) {
        int dx = to.x - from.x, dy = to.y - from.y;
        if (dx != 0) {
            IsoGridSquare edge = dx > 0 ? to : from;
            if (edge.has(IsoFlagType.collideW) || edge.has(IsoFlagType.HoppableW)
                    || edge.has(IsoFlagType.DoorWallW) || edge.has(IsoFlagType.WindowW)) return false;
        }
        if (dy != 0) {
            IsoGridSquare edge = dy > 0 ? to : from;
            if (edge.has(IsoFlagType.collideN) || edge.has(IsoFlagType.HoppableN)
                    || edge.has(IsoFlagType.DoorWallN) || edge.has(IsoFlagType.WindowN)) return false;
        }
        return true;
    }

    private static boolean route(IsoMovingObject object, IsoGridSquare from, IsoGridSquare to) {
        if (!openWater(from) && !openWater(to)) return false;
        if (!passable(from) || !passable(to)) return false;
        if (object instanceof IsoPlayer player && (GameClient.client || GameServer.server)
                && !SafeHouse.isSafehouseAllowTrepass(to, player)) return false;
        int dx = to.x - from.x, dy = to.y - from.y;
        if (Math.abs(dx) > 1 || Math.abs(dy) > 1 || !clearEdge(from, to)) return false;
        if (dx == 0 || dy == 0) return true;

        IsoGridSquare xSide = from.getCell().getGridSquare(to.x, from.y, 0);
        IsoGridSquare ySide = from.getCell().getGridSquare(from.x, to.y, 0);
        return passable(xSide) && passable(ySide) && clearEdge(from, xSide) && clearEdge(xSide, to)
            && clearEdge(from, ySide) && clearEdge(ySide, to);
    }

    public static boolean allowTransition(IsoMovingObject object, IsoGridSquare from, IsoGridSquare to) {
        if (!openWater(from) && !openWater(to)) return false;
        if (object instanceof IsoPlayer player) {
            if (!canSwim(player)) return false;
            boolean iceCrossing = WaterIce.active() && (WaterIce.supports(to) || openWater(from) && !openWater(to));
            return (iceCrossing || WaterGameplay.enabled(OPTION)) && route(player, from, to);
        }
        return object instanceof IsoZombie zombie && WaterZombies.canCross(zombie, from) && route(zombie, from, to);
    }

    public static Vector2f resolve(PolygonalMap2 map, IsoGameCharacter character, float x, float y, Vector2f out) {
        Vector2f vanilla = map.resolveCollision(character, x, y, out);
        if (vanilla.x == x && vanilla.y == y || character.getZ() >= 0.5f || character.getZ() < 0f) return vanilla;
        var from = character.getCurrentSquare();
        if (from == null || !(character instanceof IsoPlayer || character instanceof IsoZombie)) return vanilla;
        var to = from.getCell().getGridSquare(x, y, 0f);
        return allowTransition(character, from, to) ? out.set(x, y) : vanilla;
    }

    public static float movement(float vanilla, IsoPlayer player) {
        if (!isSwimming(player)) return vanilla;
        float carry = Math.max(0f, player.getInventoryWeight()) / Math.max(1, player.getMaxWeight());
        float endurance = Math.max(0f, Math.min(1f, player.getStats().get(CharacterStat.ENDURANCE)));
        return vanilla * (0.23f + 0.43f * endurance) / (1f + 0.2f * Math.min(2f, carry));
    }

    static boolean isSwimming(IsoPlayer player) {
        var square = player.getCurrentSquare();
        return canSwim(player) && openWater(square) && !WaterIce.supports(square)
            && (WaterGameplay.enabled(OPTION) || WaterIce.active());
    }

    public static float submersion(IsoPlayer player) {
        if (player == null || !canSwim(player) || !openWater(player.getCurrentSquare())
                || !WaterGameplay.enabled("PZOEWater.Drowning") || WaterIce.supports(player.getCurrentSquare())) return 0f;
        SwimSample sample = swimmers.get(player);
        return sample == null ? 0f : Math.min(1f, sample.exhaustedSeconds() / 1.5f);
    }

    public static void update(IsoPlayer player) {
        if (GameTime.isGamePaused() || (!GameServer.server && !player.isLocalPlayer())) return;
        var square = player.getCurrentSquare();
        if (!canSwim(player) || !openWater(square) || !(WaterGameplay.enabled(OPTION) || WaterIce.active())) {
            if (!swimmers.isEmpty()) swimmers.remove(player);
            return;
        }
        if (WaterIce.supports(square)) {
            WaterIce.step(player, square);
            if (!swimmers.isEmpty()) swimmers.remove(player);
            return;
        }
        float endurance = player.getStats().get(CharacterStat.ENDURANCE);
        long now = System.nanoTime();
        SwimSample previous = swimmers.get(player);
        if (previous == null) {
            swimmers.put(player, new SwimSample(now, endurance, 0f));
            return;
        }
        if (now - previous.time() < 500_000_000L) return;
        float dt = Math.min(1f, (now - previous.time()) * 1e-9f);
        float carry = Math.max(0f, player.getInventoryWeight()) / Math.max(1, player.getMaxWeight());
        float drain = (player.isPlayerMoving() ? 0.035f : 0.018f) + 0.01f * Math.min(2f, carry);
        endurance = Math.max(0f, Math.min(endurance, previous.endurance()) - drain * dt);
        player.getStats().set(CharacterStat.ENDURANCE, endurance);
        float exhausted = endurance <= 0f ? previous.exhaustedSeconds() + dt : 0f;
        swimmers.put(player, new SwimSample(now, endurance, exhausted));
        if (!GameClient.client && exhausted >= DROWN_DELAY && !player.isInvulnerable()
                && WaterGameplay.enabled("PZOEWater.Drowning")) {
            player.getBodyDamage().ReduceGeneralHealth(7f * dt);
            if (GameServer.server) INetworkPacket.send(player, PacketTypes.PacketType.PlayerDamage, player);
        }
    }

    public static boolean patchSquare(ClassNode node) {
        var method = AsmSupport.findMethod(node, "testCollideAdjacent", "(Lzombie/iso/IsoMovingObject;III)Z");
        if (method == null) return false;
        for (var insn : method.instructions) {
            if (!(insn instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETSTATIC
                    || !field.owner.equals("zombie/iso/IsoGridSquare") || !field.name.equals("useSlowCollision")) continue;
            // The destination local is available here, after vanilla safehouse and special-object checks.
            InsnList call = new InsnList();
            LabelNode useStock = new LabelNode();
            call.add(new VarInsnNode(Opcodes.ALOAD, 1));
            call.add(new VarInsnNode(Opcodes.ALOAD, 0));
            call.add(new VarInsnNode(Opcodes.ALOAD, 5));
            call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "allowTransition",
                "(Lzombie/iso/IsoMovingObject;Lzombie/iso/IsoGridSquare;Lzombie/iso/IsoGridSquare;)Z", false));
            call.add(new JumpInsnNode(Opcodes.IFEQ, useStock));
            call.add(new InsnNode(Opcodes.ICONST_0));
            call.add(new InsnNode(Opcodes.IRETURN));
            call.add(useStock);
            method.instructions.insertBefore(field, call);
            return true;
        }
        return false;
    }

    public static boolean patchMovingObject(ClassNode node) {
        var method = AsmSupport.findMethod(node, "postupdate", "()V");
        if (method == null) return false;
        MethodInsnNode collision = null, movement = null;
        for (var insn : method.instructions) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            if (call.owner.equals("zombie/pathfind/PolygonalMap2") && call.name.equals("resolveCollision")
                    && call.desc.equals("(Lzombie/characters/IsoGameCharacter;FFLorg/joml/Vector2f;)Lorg/joml/Vector2f;")) collision = call;
            if (call.owner.equals(MOVING_OBJECT) && call.name.equals("getGlobalMovementMod") && call.desc.equals("()F")) movement = call;
        }
        if (collision == null || movement == null) return false;
        method.instructions.set(collision, new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "resolve",
            "(Lzombie/pathfind/PolygonalMap2;Lzombie/characters/IsoGameCharacter;FFLorg/joml/Vector2f;)Lorg/joml/Vector2f;", false));
        InsnList call = new InsnList();
        call.add(new VarInsnNode(Opcodes.ALOAD, 0));
        call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/pzoe/water/WaterZombies", "movement",
            "(FLzombie/iso/IsoMovingObject;)F", false));
        method.instructions.insert(movement, call);
        return true;
    }
}
