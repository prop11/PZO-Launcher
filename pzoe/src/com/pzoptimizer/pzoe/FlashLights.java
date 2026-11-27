package com.pzoptimizer.pzoe;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Stack;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL20;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.math.PZMath;
import zombie.core.skinnedmodel.model.ModelSlotRenderData;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.iso.LosUtil;
import zombie.iso.Vector3;
import zombie.iso.fboRenderChunk.FBORenderCell;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.scripting.objects.ModelAttachmentId;
import zombie.vehicles.BaseVehicle;

public final class FlashLights {
    static final int MAX = 16;
    static final int KIND_SHOT = 0, KIND_EXPLOSION = 1, KIND_MOLOTOV = 2, KIND_SPARK = 3;
    private static final int AUDIO = 1, EFFECT = 2, TRACERS = 4;
    private static final float SHOT_RADIUS = 6f;
    private static final long MATCH_NS = 150_000_000L;
    private static final long SHOT_NS = 72_000_000L;
    private static final long MAX_FIRST_FRAME_NS = 120_000_000L;
    private static final float[] EMPTY = new float[0];

    static volatile boolean wanted;
    static volatile float amount = 1f;
    private static volatile int readyViews;
    private static volatile float[] published = EMPTY;

    // The remaining state is owned by the game thread.
    private static final Vector3 muzzle = new Vector3(), direction = new Vector3();
    private static final Vector3f vehicleLight = new Vector3f();
    private static final WeakHashMap<IsoGameCharacter, ShotCues> cues = new WeakHashMap<>();
    private static final ConcurrentLinkedQueue<Burst> events = new ConcurrentLinkedQueue<>();
    private static final float[] live = new float[MAX * 8];
    private static final long[] started = new long[MAX], duration = new long[MAX];
    private static final boolean[] shown = new boolean[MAX];
    private static int liveCount;
    private static WeakReference<IsoCell> world = new WeakReference<>(null);
    private static int visualCue = EFFECT;

    private static final FloatBuffer buffer = ByteBuffer.allocateDirect(MAX * 8 * Float.BYTES)
        .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private static float[] uploaded;

    private record Burst(int kind, float x, float y, int z) {}

    // Each cue claims the oldest compatible pulse once. Repeated cues from the same source are separate shots.
    private static final class ShotCues {
        private final long[] times = new long[4];
        private final int[] sources = new int[4];
        private int next;

        boolean newPulse(int source, long now) {
            for (int i = 0; i < times.length; i++) {
                int at = (next + i) % times.length;
                if (sources[at] != 0 && (sources[at] & source) == 0 && now - times[at] <= MATCH_NS) {
                    sources[at] |= source;
                    return false;
                }
            }
            times[next] = now;
            sources[next] = source;
            next = (next + 1) % times.length;
            return true;
        }
    }

    private FlashLights() {}

    /** The exact Stack.add call that submits the vanilla transient light. The stock flash model is drawn normally. */
    public static boolean submitVanilla(Stack<Object> lights, Object light, IsoGameCharacter shooter) {
        if (!active() || shooter == null) return lights.add(light);
        try {
            return pulse(shooter, visualCue) || lights.add(light);
        } catch (Exception | LinkageError ignored) {
            return lights.add(light);
        }
    }

    public static void tracerFlash(zombie.EffectsManager effects, IsoGameCharacter shooter, int probability) {
        if (!active()) {
            effects.startMuzzleFlash(shooter, probability);
            return;
        }
        int previous = visualCue;
        visualCue = TRACERS;
        try {
            effects.startMuzzleFlash(shooter, probability);
        } finally {
            visualCue = previous;
        }
    }

    public static void audio(IsoGameCharacter shooter) {
        SmokePlumes.shot(shooter);
        if (!active() || shooter == null) return;
        try {
            pulse(shooter, AUDIO);
        } catch (Exception | LinkageError ignored) {
        }
    }

    public static void remoteAudio(short playerId) {
        if (active()) audio(GameClient.IDToPlayerMap.get(playerId));
        else SmokePlumes.shot(GameClient.IDToPlayerMap.get(playerId));
    }

    private static boolean active() {
        return wanted && readyViews != 0 && !GameServer.server;
    }

    private static boolean pulse(IsoGameCharacter shooter, int source) {
        IsoCell cell = cell();
        IsoGridSquare square = shooter.getCurrentSquare();
        if (cell == null || square == null) return false;
        updateWorld(cell);
        float x = shooter.getX(), y = shooter.getY();
        int floor = square.getZ();
        if (distanceToView(x, y, readyViews) > 1600f) return false;
        long now = System.nanoTime();
        if (amount <= 0f || !cues.computeIfAbsent(shooter, ignored -> new ShotCues()).newPulse(source, now)) return true;
        float z = shooter.getZ() + 0.54f;
        var model = shooter.primaryHandModel;
        var attachment = model == null || model.modelScript == null ? null
            : model.modelScript.getAttachmentById(ModelAttachmentId.MUZZLE);
        if (attachment != null && model.animPlayer != null && model.animPlayer.isReady()) {
            model.getAttachmentWorldPosition(attachment, muzzle, direction);
            if (Math.abs(muzzle.x - x) < 2f && Math.abs(muzzle.y - y) < 2f && Math.abs(muzzle.z - z) < 1f) {
                x = muzzle.x;
                y = muzzle.y;
                z = muzzle.z;
            }
        } else {
            x += shooter.getForwardDirectionX() * 0.4f;
            y += shooter.getForwardDirectionY() * 0.4f;
        }
        int muzzleX = PZMath.fastfloor(x), muzzleY = PZMath.fastfloor(y);
        if ((muzzleX != square.getX() || muzzleY != square.getY())
            && blocked(cell, square.getX(), square.getY(), muzzleX, muzzleY, floor)) {
            x = shooter.getX();
            y = shooter.getY();
        }
        add(x, y, z, SHOT_RADIUS, 1.65f, 1.10f, 0.61f, floor, now, SHOT_NS);
        return true;
    }

    static void event(int kind, float x, float y, int z) {
        if (wanted) events.offer(new Burst(kind, x, y, z));
    }

    static void viewReady(int player) {
        int bit = 1 << player;
        if ((readyViews & bit) == 0) readyViews |= bit;
    }

    private static IsoCell cell() {
        return IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
    }

    private static void updateWorld(IsoCell cell) {
        if (world.get() == cell) return;
        world = new WeakReference<>(cell);
        liveCount = 0;
        published = EMPTY;
        cues.clear();
    }

    static void frame(float dayLight) {
        if (!wanted) {
            published = EMPTY;
            liveCount = 0;
            if (!cues.isEmpty()) cues.clear();
            if (!events.isEmpty()) events.clear();
            return;
        }
        if (liveCount == 0 && events.isEmpty()) return;
        IsoCell cell = cell();
        updateWorld(cell);
        if (cell == null || amount <= 0f) {
            published = EMPTY;
            liveCount = 0;
            events.clear();
            return;
        }

        long now = System.nanoTime();
        int kept = 0;
        for (int i = 0; i < liveCount; i++) {
            long limit = duration[i] == SHOT_NS && !shown[i] ? MAX_FIRST_FRAME_NS : duration[i];
            if (now - started[i] >= limit) continue;
            if (kept != i) {
                System.arraycopy(live, 8 * i, live, 8 * kept, 8);
                started[kept] = started[i];
                duration[kept] = duration[i];
                shown[kept] = shown[i];
            }
            kept++;
        }
        liveCount = kept;
        for (Burst e; (e = events.poll()) != null; ) {
            if (distanceToView(e.x, e.y, 15) > 1600f) continue;
            switch (e.kind) {
                case KIND_SPARK -> add(e.x, e.y, e.z + 0.5f, 2.8f, 1.15f, 0.62f, 0.18f, e.z, now, 130_000_000L);
                case KIND_EXPLOSION -> add(e.x, e.y, e.z + 0.5f, 13f, 3.2f, 2.3f, 1.3f, e.z, now, 600_000_000L);
                case KIND_MOLOTOV -> add(e.x, e.y, e.z + 0.5f, 8f, 2.0f, 1.1f, 0.4f, e.z, now, 700_000_000L);
            }
        }
        if (liveCount == 0) {
            published = EMPTY;
            return;
        }

        float dark = Math.max(0f, Math.min(1f, 1f - dayLight * 1.4f));
        float scale = amount * (0.3f + 0.7f * dark);
        float[] out = new float[liveCount * 8];
        System.arraycopy(live, 0, out, 0, out.length);
        for (int i = 0; i < liveCount; i++) {
            int offset = 8 * i;
            boolean shot = duration[i] == SHOT_NS;
            long elapsed = now - started[i];
            // A shot between slow frames should still have a visible onset.
            if (shot && !shown[i]) elapsed = Math.min(elapsed, 16_000_000L);
            shown[i] = true;
            float t = elapsed / (float) duration[i];
            float gain = shot ? (1f - t) * (1f - t)
                : (float) Math.exp(-3.5 * t) * (1f - t);
            for (int c = 4; c < 7; c++) out[offset + c] *= gain * scale;
        }
        published = out;
    }

    private static float distanceToView(float x, float y, int views) {
        float nearest = Float.POSITIVE_INFINITY;
        for (int i = 0; i < IsoPlayer.numPlayers; i++) {
            IsoPlayer p = IsoPlayer.players[i];
            if ((views & (1 << i)) == 0 || p == null) continue;
            float dx = x - p.getX(), dy = y - p.getY();
            nearest = Math.min(nearest, dx * dx + dy * dy);
        }
        return nearest;
    }

    private static void add(float x, float y, float z, float radius, float r, float g, float b, float floor, long now, long length) {
        int at = liveCount;
        if (at == MAX) {
            at = 0;
            for (int i = 1; i < MAX; i++) {
                if (started[i] + duration[i] < started[at] + duration[at]) at = i;
            }
        } else {
            liveCount++;
        }
        int o = at * 8;
        live[o] = x;
        live[o + 1] = y;
        live[o + 2] = z;
        live[o + 3] = radius;
        live[o + 4] = r;
        live[o + 5] = g;
        live[o + 6] = b;
        live[o + 7] = length == SHOT_NS ? floor + 0.5f : floor;
        started[at] = now;
        duration[at] = length;
        shown[at] = false;
    }

    /** Adds the strongest visible pulse to the model's existing local-light slots. */
    public static void model(ModelSlotRenderData slot) {
        float[] pulses = published;
        if (pulses.length == 0 || slot.debugChunkState || slot.object == null) return;
        IsoGridSquare square = slot.object.getCurrentSquare();
        if (square == null) return;
        IsoCell cell = cell();
        if (cell == null) return;

        int best = -1;
        float power = 0f, weight = 0f;
        for (int i = 0; i < pulses.length; i += 8) {
            if (pulses[i + 7] != square.getZ() + 0.5f) continue;
            float dx = pulses[i] - slot.x, dy = pulses[i + 1] - slot.y, dz = pulses[i + 2] - slot.z - 0.42f;
            float d2 = dx * dx + dy * dy + 6f * dz * dz;
            float radius = pulses[i + 3], q = d2 / (radius * radius);
            if (q >= 1f) continue;
            float window = 1f - q * q;
            float falloff = 1.5f * window * window / (1.5f + d2);
            float strength = pulses[i + 4] * falloff;
            if (strength <= power) continue;
            if (blocked(cell, PZMath.fastfloor(pulses[i]), PZMath.fastfloor(pulses[i + 1]), square.getX(), square.getY(),
                square.getZ())) continue;
            power = strength;
            weight = falloff;
            best = i;
        }
        if (best < 0 || FBORenderCell.instance.isBlackedOutBuildingSquare(square)) return;
        int target = -1;
        float weakest = power;
        for (int i = 0; i < slot.effectLights.length; i++) {
            var light = slot.effectLights[i];
            float strength = light.radius <= 0 ? 0f : Math.max(light.r, Math.max(light.g, light.b));
            if (strength < weakest) {
                weakest = strength;
                target = i;
                if (strength == 0f) break;
            }
        }
        if (target < 0) return;

        float x = pulses[best], y = pulses[best + 1], z = pulses[best + 2];
        if (slot.object instanceof BaseVehicle vehicle) {
            vehicle.getLocalPos(x, y, z, vehicleLight);
            x = vehicleLight.x;
            y = vehicleLight.y + (z - slot.z) * 2.44949f;
            z = vehicleLight.z;
        }
        // Keep the game's additional distance attenuation small.
        slot.effectLights[target].set(x, y, z, pulses[best + 4] * weight,
            pulses[best + 5] * weight, pulses[best + 6] * weight, 32);
    }

    private static boolean blocked(IsoCell cell, int x, int y, int targetX, int targetY, int floor) {
        var los = LosUtil.lineClear(cell, x, y, floor, targetX, targetY, floor, false);
        return los == LosUtil.TestResults.Blocked || los == LosUtil.TestResults.ClearThroughClosedDoor;
    }

    /** Returns the count only for chunks intersecting a live light's padded world-space bounds. */
    static int upload(int location, float x, float y) {
        float[] pulses = published;
        if (pulses.length == 0 || location < 0) return 0;
        for (int i = 0; i < pulses.length; i += 8) {
            float r = pulses[i + 3] + 2f;
            if (pulses[i] + r < x || pulses[i] - r > x + 8f || pulses[i + 1] + r < y || pulses[i + 1] - r > y + 8f) continue;
            if (pulses != uploaded) {
                buffer.clear();
                buffer.put(pulses);
                buffer.flip();
                GL20.glUniform4fv(location, buffer);
                uploaded = pulses;
            }
            return pulses.length / 8;
        }
        return 0;
    }

    static void invalidate() {
        uploaded = null;
        readyViews = 0;
    }

    static String stateText() {
        return "burstWanted=" + (wanted ? 1 : 0) + ";bursts=" + (published.length / 8);
    }
}
