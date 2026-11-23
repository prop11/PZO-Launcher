package com.pzoptimizer.pzoe.water;

import com.pzoptimizer.AsmSupport;
import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.util.WeakHashMap;
import org.joml.Vector3f;
import zombie.GameTime;
import zombie.core.physics.Bullet;
import zombie.core.physics.CarController;
import zombie.iso.IsoWorld;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.vehicles.BaseVehicle;

public final class WaterVehicles {
    private static final String OWNER = "com/pzoptimizer/pzoe/water/WaterVehicles";
    private static final String OPTION = "PZOEWater.Vehicles";
    private static final long SUBMERGED_NANOS = 5_000_000_000L;
    private static final WeakHashMap<BaseVehicle, Immersion> submerged = new WeakHashMap<>();
    private static final Vector3f point = new Vector3f();

    private record Immersion(long last, long elapsed) {}

    private WaterVehicles() {}

    public static void control(int id, float engine, float brake, float steering, CarController controller) {
        if (WaterGameplay.enabled(OPTION)) {
            BaseVehicle vehicle = controller.vehicleObject;
            float depth = WaterSurface.depthAt(vehicle.getSquare(), vehicle.getX(), vehicle.getY());
            if (depth > 0.4f) {
                engine *= 1f - 0.78f * depth;
                brake = Math.max(brake, depth * Math.min(12f, Math.abs(vehicle.getCurrentSpeedKmHour()) * 0.4f));
            }
        }
        Bullet.controlVehicle(id, engine, brake, steering);
    }

    public static void update(BaseVehicle vehicle) {
        if (GameClient.client) return;
        if (!WaterGameplay.enabled(OPTION)) {
            if (!submerged.isEmpty()) submerged.clear();
            return;
        }
        if (GameTime.isGamePaused() || vehicle.getScript() == null || vehicle.getScript().getWheelCount() == 0) return;
        var square = vehicle.getSquare();
        if (square == null || square.z != 0 || vehicle.getZ() >= 0.5f) {
            if (!submerged.isEmpty()) submerged.remove(vehicle);
            return;
        }
        float bodyDepth = WaterSurface.depthAt(square, vehicle.getX(), vehicle.getY());
        float halfLength = vehicle.getScript().getExtents().z * 0.42f;
        float hoodDepth = endDepth(vehicle, halfLength);
        if (hoodDepth >= 0.95f && vehicle.isEngineRunning()) vehicle.engineDoStalling();

        if (bodyDepth < 0.95f || hoodDepth < 0.95f || endDepth(vehicle, -halfLength) < 0.95f) {
            if (!submerged.isEmpty()) submerged.remove(vehicle);
            return;
        }
        var engine = vehicle.getPartById("Engine");
        if (engine == null || engine.getCondition() <= 0) return;
        long now = System.nanoTime();
        Immersion before = submerged.get(vehicle);
        long elapsed = before == null ? 0 : before.elapsed() + Math.min(250_000_000L, now - before.last());
        if (elapsed < SUBMERGED_NANOS) {
            submerged.put(vehicle, new Immersion(now, elapsed));
            return;
        }
        submerged.remove(vehicle);
        engine.setCondition(0);
        if (GameServer.server) vehicle.transmitPartCondition(engine);
    }

    private static float endDepth(BaseVehicle vehicle, float localZ) {
        var world = IsoWorld.instance;
        if (world == null || world.currentCell == null) return 0f;
        vehicle.getWorldPos(0f, 0f, localZ, point);
        return WaterSurface.depthAt(world.currentCell.getGridSquare(point.x, point.y, 0f), point.x, point.y);
    }

    public static boolean patchController(ClassNode node) {
        var update = AsmSupport.findMethod(node, "update", "()V");
        if (update == null) return false;
        int hooks = 0;
        for (var insn = update.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !call.owner.equals("zombie/core/physics/Bullet") || !call.name.equals("controlVehicle")
                    || !call.desc.equals("(IFFF)V")) continue;
            update.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, 0));
            call.owner = OWNER;
            call.name = "control";
            call.desc = "(IFFFLzombie/core/physics/CarController;)V";
            hooks++;
        }
        return hooks > 0;
    }

    public static boolean patchVehicle(ClassNode node) {
        var update = AsmSupport.findMethod(node, "update", "()V");
        if (update == null) return false;
        AsmSupport.prependCall(update, OWNER, "update", "(Lzombie/vehicles/BaseVehicle;)V");
        return true;
    }
}
