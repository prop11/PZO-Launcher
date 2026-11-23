package com.pzoptimizer.pzoe.water;

import com.pzoptimizer.AsmSupport;
import com.pzoptimizer.pzoe.PuddleSplash;
import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import zombie.GameTime;
import zombie.inventory.types.Clothing;
import zombie.iso.IsoCell;
import zombie.iso.IsoWaterFlow;
import zombie.iso.objects.IsoDeadBody;
import zombie.iso.objects.IsoWorldInventoryObject;
import zombie.network.GameClient;

public final class WaterBuoyancy {
    public static final String ITEM = "zombie/iso/objects/IsoWorldInventoryObject";
    public static final String CELL = "zombie/iso/IsoCell";
    private static final String OWNER = "com/pzoptimizer/pzoe/water/WaterBuoyancy";
    private static final String OPTION = "PZOEWater.Buoyancy";
    private static final long TICK_NANOS = 1_000_000_000L;
    private static final int BATCH = 20;
    private static final LinkedHashSet<IsoWorldInventoryObject> items = new LinkedHashSet<>();
    private static final ArrayList<IsoWorldInventoryObject> next = new ArrayList<>(BATCH);
    private static long lastTick;

    private WaterBuoyancy() {}

    public static void track(IsoWorldInventoryObject object) {
        if (GameClient.client || !WaterSwimming.openWater(object.getSquare()) || !WaterGameplay.enabled(OPTION)) return;
        items.add(object);
    }

    public static void untrack(IsoWorldInventoryObject object) {
        if (!items.isEmpty()) items.remove(object);
    }

    public static void dropped(IsoWorldInventoryObject object) {
        track(object);
        var square = object.getSquare();
        if (square == null || object.getOffZ() > 0.15f) return;
        PuddleSplash.waterImpact(square, square.x + object.getOffX(), square.y + object.getOffY(), 0.54f);
    }

    public static void update(IsoCell cell) {
        if (GameClient.client || items.isEmpty() || !WaterGameplay.enabled(OPTION)) return;
        long now = System.nanoTime();
        if (now - lastTick < TICK_NANOS || GameTime.isGamePaused()) return;
        lastTick = now;
        Iterator<IsoWorldInventoryObject> iterator = items.iterator();
        for (int checked = 0; checked < BATCH && iterator.hasNext(); checked++) {
            IsoWorldInventoryObject object = iterator.next();
            iterator.remove();
            var square = object.getSquare();
            var item = object.getItem();
            if (item == null || item.getWorldItem() != object || !WaterSwimming.openWater(square)
                    || square.getCell() != cell || object.getObjectIndex() < 0) continue;
            next.add(object);
            if (WaterIce.supports(square)) continue;

            if (item instanceof Clothing clothing && clothing.getWetness() < 100f) {
                clothing.setWetness(Math.min(100f, clothing.getWetness() + 25f));
                object.flagForHotSave();
            }
            float x = object.getOffX(), y = object.getOffY(), z = object.getOffZ();
            if (item.getActualWeight() + item.getContentsWeight() > 1.2f) {
                if (z != -0.12f) object.setOffset(x, y, -0.12f);
                continue;
            }
            var flow = IsoWaterFlow.getPoints().isEmpty() ? null : IsoWaterFlow.getNearest(square.x, square.y);
            if (flow != null && flow.speed > 0f) {
                float drift = Math.min(0.16f, flow.speed * 0.04f);
                x = clampOffset(x + (float) Math.sin(flow.radians) * drift);
                y = clampOffset(y + (float) Math.cos(flow.radians) * drift);
            }
            if (Math.abs(x - object.getOffX()) > 0.01f || Math.abs(y - object.getOffY()) > 0.01f || z != 0.012f) {
                object.setOffset(x, y, 0.012f);
            }
        }
        items.addAll(next);
        next.clear();
    }

    private static float clampOffset(float offset) {
        return Math.max(0.06f, Math.min(0.94f, offset));
    }

    public static float corpseHeight(float z, IsoDeadBody body) {
        if (!WaterSwimming.openWater(body.getSquare()) || !WaterGameplay.enabled(OPTION)
                || WaterIce.supports(body.getSquare())) return z;
        double wave = System.nanoTime() % 3_100_000_000L * (2.0 * Math.PI / 3_100_000_000.0);
        return z + 0.014f + (float) Math.sin(wave + body.getX() * 0.7f + body.getY() * 0.4f) * 0.012f;
    }

    public static void bodyImpact(IsoDeadBody body, boolean existing, boolean added) {
        if (!existing && added) PuddleSplash.waterImpact(body.getSquare(), body.getX(), body.getY(), 0.98f);
    }

    public static boolean patchItem(ClassNode node) {
        var add = AsmSupport.findMethod(node, "addToWorld", "()V");
        var remove = AsmSupport.findMethod(node, "removeFromWorld", "()V");
        var create = AsmSupport.findMethod(node, "<init>", "(Lzombie/inventory/InventoryItem;Lzombie/iso/IsoGridSquare;FFF)V");
        if (add == null || remove == null || create == null) return false;
        String signature = "(L" + ITEM + ";)V";
        AsmSupport.prependCall(add, OWNER, "track", signature);
        AsmSupport.prependCall(remove, OWNER, "untrack", signature);
        for (var insn = create.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.RETURN) continue;
            InsnList call = new InsnList();
            call.add(new VarInsnNode(Opcodes.ALOAD, 0));
            call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "dropped", signature, false));
            create.instructions.insertBefore(insn, call);
        }
        return true;
    }

    public static boolean patchCell(ClassNode node) {
        var method = AsmSupport.findMethod(node, "update", "()V");
        if (method == null) return false;
        AsmSupport.prependCall(method, OWNER, "update", "(L" + CELL + ";)V");
        return true;
    }

    public static boolean patchCorpse(ClassNode node) {
        var method = AsmSupport.findMethod(node, "render", "(FFFLzombie/core/textures/ColorInfo;ZZLzombie/core/opengl/Shader;)V");
        var constructor = AsmSupport.findMethod(node, "<init>", "(Lzombie/characters/IsoGameCharacter;ZZ)V");
        if (method == null && constructor == null) return false;
        if (constructor != null) {
            for (var insn = constructor.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() != Opcodes.RETURN) continue;
                InsnList impact = new InsnList();
                impact.add(new VarInsnNode(Opcodes.ALOAD, 0));
                impact.add(new VarInsnNode(Opcodes.ILOAD, 2));
                impact.add(new VarInsnNode(Opcodes.ILOAD, 3));
                impact.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "bodyImpact",
                    "(Lzombie/iso/objects/IsoDeadBody;ZZ)V", false));
                constructor.instructions.insertBefore(insn, impact);
            }
        }
        if (method == null) return true;
        InsnList call = new InsnList();
        call.add(new VarInsnNode(Opcodes.FLOAD, 3));
        call.add(new VarInsnNode(Opcodes.ALOAD, 0));
        call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "corpseHeight", "(FLzombie/iso/objects/IsoDeadBody;)F", false));
        call.add(new VarInsnNode(Opcodes.FSTORE, 3));
        method.instructions.insert(call);
        return true;
    }
}
