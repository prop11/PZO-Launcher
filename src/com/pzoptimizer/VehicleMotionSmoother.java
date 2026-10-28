package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.FieldInsnNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.InsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.physics.Bullet;
import zombie.core.physics.Transform;
import zombie.iso.IsoCamera;
import zombie.iso.IsoWorld;
import zombie.iso.PlayerCamera;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.BaseVehicle.WheelInfo;

/**
 * Custom motion smoother for Project Zomboid vehicles: eliminates the 60 Hz physics
 * vs display refresh rate (120/144/165 Hz) mismatch jitter by applying transient SLERP
 * (rotation) and LERP (translation, wheels, passengers) interpolation right before
 * rendering, then restoring true physics states immediately afterwards.
 */
public final class VehicleMotionSmoother {

    public static volatile boolean active = true;

    // Bullet physics snapshot storage
    private static final HashMap<Integer, VehicleSnapshot> prevSnapshots = new HashMap<>();
    private static final HashMap<Integer, VehicleSnapshot> currSnapshots = new HashMap<>();
    private static final float[] bulletBuffer = new float[8192];
    private static long currentStamp = 0;
    private static float currentAlpha = 0.0f;

    // Transient render restore pool (zero runtime allocations per frame)
    private static final ArrayList<RenderRestore> activeRestores = new ArrayList<>(16);
    private static final ArrayList<RenderRestore> restorePool = new ArrayList<>(16);

    // Scratch math primitives
    private static final Quaternionf scratchQuatA = new Quaternionf();
    private static final Quaternionf scratchQuatB = new Quaternionf();
    private static final Quaternionf scratchQuatOut = new Quaternionf();
    private static final Vector3f scratchSeatPos = new Vector3f();

    // Camera state preservation
    private static boolean cameraSaved = false;
    private static float savedCamOffX = 0.0f;
    private static float savedCamOffY = 0.0f;
    private static float savedCamTOffX = 0.0f;
    private static float savedCamTOffY = 0.0f;

    // Method handles for private/protected game engine members
    private static MethodHandle updateTransformHandle = null;
    private static MethodHandle getTOffXHandle = null;
    private static MethodHandle setTOffXHandle = null;
    private static MethodHandle getTOffYHandle = null;
    private static MethodHandle setTOffYHandle = null;
    private static volatile boolean handlesResolved = false;
    private static volatile boolean handlesUnavailable = false;

    private VehicleMotionSmoother() {}

    private static synchronized boolean resolveHandles() {
        if (handlesResolved) return !handlesUnavailable;
        handlesResolved = true;

        try {
            Method m = BaseVehicle.class.getDeclaredMethod("updateTransform");
            m.setAccessible(true);
            updateTransformHandle = MethodHandles.lookup().unreflect(m);

            Field fX = PlayerCamera.class.getDeclaredField("tOffX");
            fX.setAccessible(true);
            getTOffXHandle = MethodHandles.lookup().unreflectGetter(fX);
            setTOffXHandle = MethodHandles.lookup().unreflectSetter(fX);

            Field fY = PlayerCamera.class.getDeclaredField("tOffY");
            fY.setAccessible(true);
            getTOffYHandle = MethodHandles.lookup().unreflectGetter(fY);
            setTOffYHandle = MethodHandles.lookup().unreflectSetter(fY);

            PZOLogger.success("[VehicleMotionSmoother] Engine MethodHandles resolved (Fast-Path Transform Inlined)");
            return true;
        } catch (Throwable t) {
            handlesUnavailable = true;
            PZOLogger.warn("[VehicleMotionSmoother] Reflection resolution notice: " + t);
            return false;
        }
    }

    public static boolean isEnabled() {
        return active && !GameServer.server && !GameClient.client;
    }

    /**
     * Hooked in WorldSimulation.updatePhysic() right before each Bullet substep simulation.
     * When this is the final substep before completion, captures the 'prev' snapshot.
     */
    public static void onPhysicsSubStep(int currentSubStep, int totalSubSteps) {
        if (!isEnabled()) return;
        if (currentSubStep == totalSubSteps - 1) {
            readSnapshots(prevSnapshots, currentStamp + 1);
        }
    }

    /**
     * Hooked in WorldSimulation.updatePhysic() after all substeps complete.
     * Captures the 'curr' snapshot, advances the stamp, and computes the fractional alpha.
     */
    public static void afterPhysicsSteps(int totalSubSteps, float localTime, float fixedTimeStep) {
        if (!isEnabled()) return;

        if (totalSubSteps > 0) {
            currentStamp++;
            readSnapshots(currSnapshots, currentStamp);
        }

        if (fixedTimeStep > 0.0001f) {
            currentAlpha = Math.max(0.0f, Math.min(1.0f, localTime / fixedTimeStep));
        } else {
            currentAlpha = 1.0f;
        }
    }

    private static void readSnapshots(HashMap<Integer, VehicleSnapshot> map, long stamp) {
        try {
            int total = Bullet.getVehicleCount();
            int offset = 0;
            while (offset < total) {
                int num = Bullet.getVehiclePhysics(offset, bulletBuffer);
                if (num <= 0) break;
                offset += num;
                int idx = 0;
                for (int i = 0; i < num; i++) {
                    int id = (int) bulletBuffer[idx++];
                    float x = bulletBuffer[idx++];
                    float y = bulletBuffer[idx++];
                    float z = bulletBuffer[idx++];
                    float qx = bulletBuffer[idx++];
                    float qy = bulletBuffer[idx++];
                    float qz = bulletBuffer[idx++];
                    float qw = bulletBuffer[idx++];
                    idx += 3; // vx, vy, vz
                    idx++;    // speed
                    idx++;    // isCollide
                    int wheelCount = (int) bulletBuffer[idx++];

                    VehicleSnapshot snap = map.get(id);
                    if (snap == null) {
                        snap = new VehicleSnapshot();
                        map.put(id, snap);
                    }
                    snap.set(id, x, y, z, qx, qy, qz, qw, wheelCount, bulletBuffer, idx, stamp);
                    idx += wheelCount * 4;
                }
            }
        } catch (Throwable t) {
            // Ignore native read errors
        }
    }

    /**
     * Hooked at the very start of GameWindow.renderInternal().
     * Interpolates vehicles, wheels, and camera to match monitor refresh rate.
     */
    public static void beforeRender() {
        restoreRenderState();
        if (!isEnabled() || !resolveHandles()) return;

        IsoWorld world = IsoWorld.instance;
        if (world == null || world.currentCell == null) return;

        Set<BaseVehicle> vehicles = world.currentCell.getVehicles();
        if (vehicles == null || vehicles.isEmpty()) return;

        float t = currentAlpha;
        boolean vehicleMoved = false;

        try {
            for (BaseVehicle v : vehicles) {
                if (v == null) continue;
                int id = v.vehicleId;
                VehicleSnapshot a = prevSnapshots.get(id);
                VehicleSnapshot b = currSnapshots.get(id);
                if (a == null || b == null || b.stamp != currentStamp || a.stamp != b.stamp) {
                    continue;
                }

                Transform jni = v.jniTransform;
                if (jni == null) continue;

                // Parity check with curr snapshot: if jni origin diverged significantly, don't interpolate
                if (Math.abs(jni.origin.x - b.posX) > 0.05f || Math.abs(jni.origin.z - b.posZ) > 0.05f) {
                    continue;
                }

                float dx = b.posX - a.posX;
                float dz = b.posZ - a.posZ;
                if (dx * dx + dz * dz > 1.0f) {
                    // Collision or teleport spike: avoid interpolation smear
                    continue;
                }
                if (dx == 0.0f && dz == 0.0f && a.rotEquals(b)) {
                    // Parked or static vehicle: zero transform work required
                    continue;
                }

                RenderRestore restore = restorePool.isEmpty() ? new RenderRestore() : restorePool.remove(restorePool.size() - 1);
                restore.capture(v);
                activeRestores.add(restore);

                // 1. Interpolate translation
                jni.origin.x = a.posX + (b.posX - a.posX) * t;
                jni.origin.y = a.posY + (b.posY - a.posY) * t;
                jni.origin.z = a.posZ + (b.posZ - a.posZ) * t;

                // 2. Interpolate orientation via SLERP
                scratchQuatA.set(a.rotX, a.rotY, a.rotZ, a.rotW);
                scratchQuatB.set(b.rotX, b.rotY, b.rotZ, b.rotW);
                scratchQuatA.slerp(scratchQuatB, t, scratchQuatOut);
                jni.setRotation(scratchQuatOut.normalize());

                // 3. Interpolate wheels
                int wheels = Math.min(b.wheelCount, v.wheelInfo != null ? v.wheelInfo.length : 0);
                for (int w = 0; w < wheels; w++) {
                    WheelInfo wi = v.wheelInfo[w];
                    if (wi != null) {
                        wi.steering = a.wheelSteer[w] + (b.wheelSteer[w] - a.wheelSteer[w]) * t;
                        wi.rotation = lerpAngle(a.wheelRotation[w], b.wheelRotation[w], t);
                        wi.suspensionLength = a.wheelSuspension[w] + (b.wheelSuspension[w] - a.wheelSuspension[w]) * t;
                    }
                }

                // 4. Update world coordinates for rendering
                float worldDx = jni.origin.x - restore.origJniX;
                float worldDy = jni.origin.z - restore.origJniZ;
                v.setX(restore.origWorldX + worldDx);
                v.setY(restore.origWorldY + worldDy);

                // 5. Compute chassis and model matrices
                updateTransformHandle.invokeExact(v);

                // 6. Smooth seated passengers
                int seats = v.getMaxPassengers();
                for (int p = 0; p < seats; p++) {
                    IsoGameCharacter chr = v.getCharacter(p);
                    if (chr != null) {
                        restore.addCharacter(chr);
                        Vector3f seatPos = v.getPassengerWorldPos(p, scratchSeatPos);
                        if (seatPos != null) {
                            chr.setX(seatPos.x);
                            chr.setY(seatPos.y);
                            chr.setZ(seatPos.z);
                        }
                    }
                }

                vehicleMoved = true;
            }

            // 7. Center camera smoothly on the driving vehicle
            if (vehicleMoved) {
                smoothCamera();
            }
        } catch (Throwable ex) {
            PZOLogger.warn("[VehicleMotionSmoother] Render interpolation error: " + ex);
            restoreRenderState();
        }
    }

    private static void smoothCamera() throws Throwable {
        if (IsoPlayer.numPlayers > 1) return;
        IsoPlayer player = IsoPlayer.players[0];
        if (player == null || player.getVehicle() == null) return;

        PlayerCamera cam = IsoCamera.cameras[0];
        if (cam == null) return;

        savedCamOffX = cam.offX;
        savedCamOffY = cam.offY;
        savedCamTOffX = (float) getTOffXHandle.invokeExact(cam);
        savedCamTOffY = (float) getTOffYHandle.invokeExact(cam);
        cameraSaved = true;

        IsoGameCharacter prevChr = IsoCamera.getCameraCharacter();
        IsoCamera.setCameraCharacter(player);
        cam.center();
        IsoCamera.setCameraCharacter(prevChr);
    }

    /**
     * Hooked before every RETURN in GameWindow.renderInternal().
     * Restores all vehicles, wheels, and camera state back to true physics.
     */
    public static void afterRender() {
        restoreRenderState();
    }

    private static void restoreRenderState() {
        if (activeRestores.isEmpty() && !cameraSaved) return;

        for (int i = activeRestores.size() - 1; i >= 0; i--) {
            RenderRestore restore = activeRestores.get(i);
            restore.restore(updateTransformHandle);
            restorePool.add(restore);
        }
        activeRestores.clear();

        if (cameraSaved) {
            PlayerCamera cam = IsoCamera.cameras[0];
            if (cam != null && setTOffXHandle != null && setTOffYHandle != null) {
                cam.offX = savedCamOffX;
                cam.offY = savedCamOffY;
                try {
                    setTOffXHandle.invokeExact(cam, savedCamTOffX);
                    setTOffYHandle.invokeExact(cam, savedCamTOffY);
                } catch (Throwable ignored) {}
            }
            cameraSaved = false;
        }
    }

    public static float lerpAngle(float a, float b, float t) {
        float diff = b - a;
        float twoPi = (float) (Math.PI * 2.0);
        diff -= twoPi * (float) Math.floor((diff + Math.PI) / twoPi);
        return a + diff * t;
    }

    /**
     * ASM Transformer for zombie.core.physics.WorldSimulation:
     * Inserts onPhysicsSubStep before Bullet.stepSimulation and afterPhysicsSteps after all substeps.
     */
    public static boolean patchWorldSimulation(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "updatePhysic", "()V");
        if (m == null) return false;

        boolean patchedSubStep = false;
        boolean patchedAfterSteps = false;

        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!patchedSubStep && insn instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESTATIC
                    && "zombie/core/physics/Bullet".equals(mi.owner) && "stepSimulation".equals(mi.name)) {
                // Insert: onPhysicsSubStep(i, numSimulationSubSteps)
                InsnList pre = new InsnList();
                pre.add(new VarInsnNode(Opcodes.ILOAD, 3)); // i (current substep)
                pre.add(new VarInsnNode(Opcodes.ILOAD, 2)); // numSimulationSubSteps
                pre.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "com/pzoptimizer/VehicleMotionSmoother",
                    "onPhysicsSubStep",
                    "(II)V",
                    false
                ));
                m.instructions.insertBefore(mi.getPrevious().getPrevious().getPrevious(), pre);
                patchedSubStep = true;
            }

            if (!patchedAfterSteps && insn instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.GETFIELD
                    && "zombie/core/physics/WorldSimulation".equals(fi.owner) && "time".equals(fi.name) && "J".equals(fi.desc)) {
                // Target of the substep loop exit / iflt branch:
                // Insert: afterPhysicsSteps(numSimulationSubSteps, this.localTime, fixedTimeStep)
                AbstractInsnNode prev = fi.getPrevious(); // aload_0
                if (prev != null && prev.getOpcode() == Opcodes.ALOAD) {
                    InsnList post = new InsnList();
                    post.add(new VarInsnNode(Opcodes.ILOAD, 2)); // numSimulationSubSteps
                    post.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this
                    post.add(new FieldInsnNode(Opcodes.GETFIELD, "zombie/core/physics/WorldSimulation", "localTime", "F"));
                    post.add(new VarInsnNode(Opcodes.FLOAD, 1)); // fixedTimeStep (0.01f)
                    post.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "com/pzoptimizer/VehicleMotionSmoother",
                        "afterPhysicsSteps",
                        "(IFF)V",
                        false
                    ));
                    m.instructions.insertBefore(prev, post);
                    patchedAfterSteps = true;
                }
            }
        }

        return patchedSubStep && patchedAfterSteps;
    }

    /**
     * ASM Transformer for zombie.GameWindow:
     * Wraps renderInternal() with beforeRender() at method start and afterRender() at each return.
     */
    public static boolean patchGameWindow(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "renderInternal", "()V");
        if (m == null) return false;

        // Head injection
        InsnList head = new InsnList();
        head.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "com/pzoptimizer/VehicleMotionSmoother",
            "beforeRender",
            "()V",
            false
        ));
        m.instructions.insert(head);

        // Tail injection before each RETURN
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.RETURN) {
                InsnList tail = new InsnList();
                tail.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "com/pzoptimizer/VehicleMotionSmoother",
                    "afterRender",
                    "()V",
                    false
                ));
                m.instructions.insertBefore(insn, tail);
            }
        }

        return true;
    }

    // ------------------------------------------------------------------------
    // Snapshot and Restore Pools
    // ------------------------------------------------------------------------

    private static final class VehicleSnapshot {
        int id;
        long stamp;
        float posX, posY, posZ;
        float rotX, rotY, rotZ, rotW;
        int wheelCount;
        final float[] wheelSteer = new float[4];
        final float[] wheelRotation = new float[4];
        final float[] wheelSkid = new float[4];
        final float[] wheelSuspension = new float[4];

        void set(int id, float x, float y, float z, float qx, float qy, float qz, float qw,
                 int wheels, float[] buffer, int offset, long stamp) {
            this.id = id;
            this.posX = x;
            this.posY = y;
            this.posZ = z;
            this.rotX = qx;
            this.rotY = qy;
            this.rotZ = qz;
            this.rotW = qw;
            this.wheelCount = Math.min(4, wheels);
            this.stamp = stamp;

            int idx = offset;
            for (int w = 0; w < this.wheelCount; w++) {
                this.wheelSteer[w] = buffer[idx];
                this.wheelRotation[w] = buffer[idx + 1];
                this.wheelSkid[w] = buffer[idx + 2];
                this.wheelSuspension[w] = buffer[idx + 3];
                idx += 4;
            }
        }

        boolean rotEquals(VehicleSnapshot other) {
            return Math.abs(this.rotX - other.rotX) < 1e-4f &&
                   Math.abs(this.rotY - other.rotY) < 1e-4f &&
                   Math.abs(this.rotZ - other.rotZ) < 1e-4f &&
                   Math.abs(this.rotW - other.rotW) < 1e-4f;
        }
    }

    private static final class RenderRestore {
        BaseVehicle vehicle;
        float origJniX, origJniY, origJniZ;
        float origRotX, origRotY, origRotZ, origRotW;
        float origWorldX, origWorldNextX;
        float origWorldY, origWorldNextY;
        float origWorldZ, origWorldLastZ;
        int wheelCount;
        final float[] origWheelSteer = new float[4];
        final float[] origWheelRotation = new float[4];
        final float[] origWheelSkid = new float[4];
        final float[] origWheelSuspension = new float[4];

        final ArrayList<IsoGameCharacter> characters = new ArrayList<>(4);
        final float[][] charCoords = new float[4][6];
        private static final Quaternionf tempRot = new Quaternionf();

        void capture(BaseVehicle v) {
            this.vehicle = v;
            Transform jni = v.jniTransform;
            this.origJniX = jni.origin.x;
            this.origJniY = jni.origin.y;
            this.origJniZ = jni.origin.z;

            jni.getRotation(tempRot);
            this.origRotX = tempRot.x;
            this.origRotY = tempRot.y;
            this.origRotZ = tempRot.z;
            this.origRotW = tempRot.w;

            this.origWorldX = v.getX();
            this.origWorldNextX = v.getNextX();
            this.origWorldY = v.getY();
            this.origWorldNextY = v.getNextY();
            this.origWorldZ = v.getZ();
            this.origWorldLastZ = v.getLastZ();

            int wheels = v.wheelInfo != null ? Math.min(4, v.wheelInfo.length) : 0;
            this.wheelCount = wheels;
            for (int w = 0; w < wheels; w++) {
                WheelInfo wi = v.wheelInfo[w];
                if (wi != null) {
                    this.origWheelSteer[w] = wi.steering;
                    this.origWheelRotation[w] = wi.rotation;
                    this.origWheelSkid[w] = wi.skidInfo;
                    this.origWheelSuspension[w] = wi.suspensionLength;
                }
            }
            this.characters.clear();
        }

        void addCharacter(IsoGameCharacter c) {
            if (c == null) return;
            int idx = this.characters.size();
            if (idx < 4) {
                this.characters.add(c);
                float[] q = this.charCoords[idx];
                q[0] = c.getX();
                q[1] = c.getNextX();
                q[2] = c.getY();
                q[3] = c.getNextY();
                q[4] = c.getZ();
                q[5] = c.getLastZ();
            }
        }

        void restore(MethodHandle updateHandle) {
            if (this.vehicle == null) return;
            BaseVehicle v = this.vehicle;

            v.jniTransform.origin.set(this.origJniX, this.origJniY, this.origJniZ);
            tempRot.set(this.origRotX, this.origRotY, this.origRotZ, this.origRotW);
            v.jniTransform.setRotation(tempRot);

            v.setX(this.origWorldX);
            v.setNextX(this.origWorldNextX);
            v.setY(this.origWorldY);
            v.setNextY(this.origWorldNextY);
            v.setZ(this.origWorldZ);
            v.setLastZ(this.origWorldLastZ);

            for (int w = 0; w < this.wheelCount; w++) {
                WheelInfo wi = v.wheelInfo[w];
                if (wi != null) {
                    wi.steering = this.origWheelSteer[w];
                    wi.rotation = this.origWheelRotation[w];
                    wi.skidInfo = this.origWheelSkid[w];
                    wi.suspensionLength = this.origWheelSuspension[w];
                }
            }

            for (int i = 0; i < this.characters.size(); i++) {
                IsoGameCharacter c = this.characters.get(i);
                float[] q = this.charCoords[i];
                c.setX(q[0]);
                c.setNextX(q[1]);
                c.setY(q[2]);
                c.setNextY(q[3]);
                c.setZ(q[4]);
                c.setLastZ(q[5]);
            }
            this.characters.clear();

            if (updateHandle != null) {
                try {
                    updateHandle.invokeExact(v);
                } catch (Throwable ignored) {}
            }

            this.vehicle = null;
        }
    }
}
