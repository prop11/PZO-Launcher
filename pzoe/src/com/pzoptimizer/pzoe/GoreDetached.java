package com.pzoptimizer.pzoe;

import com.pzoptimizer.pzoe.gore.GoreCore;
import com.pzoptimizer.pzoe.gore.GoreState;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import zombie.characters.IsoGameCharacter;
import zombie.core.skinnedmodel.DeadBodyAtlas;
import zombie.core.skinnedmodel.DeadBodyAtlas.BodyParams;
import zombie.core.skinnedmodel.DeadBodyAtlas.BodyTexture;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.advancedanimation.AnimatedModel;
import zombie.core.skinnedmodel.visual.HumanVisual;
import zombie.core.skinnedmodel.visual.IHumanVisual;
import zombie.core.skinnedmodel.visual.ItemVisual;
import zombie.core.skinnedmodel.visual.ItemVisuals;
import zombie.core.textures.Texture;
import zombie.iso.IsoDirections;

public final class GoreDetached {
    private static final String SLOT = "PZOEDetachedSlot", ROOT = "PZOEDetachedRoot", OMIT = "PZOEDetachedOmit";
    private static final int ATLAS_SIZE = 128, MAX_CAPTURES = 6;
    private static final Part[] pool = new Part[MAX_CAPTURES];
    private static final GoreState[][] records = new GoreState[2][9];
    private static final BodyParams params = new BodyParams();
    private static final ItemVisuals worn = new ItemVisuals();
    private static Part pending, inFlight;
    private static float nextBake, bakeDeadline;
    private static volatile boolean boundsHook;

    private GoreDetached() {}

    static final class Part {
        final int slot;
        int root, omit;
        boolean inUse;
        BodyTexture texture;
        Appearance appearance;

        Part(int slot) { this.slot = slot; }

        boolean isReady() {
            if (texture == null || Access.INSTANCE == null) return false;
            Object entry = Access.INSTANCE.entry.get(texture);
            return entry != null && (boolean) Access.INSTANCE.ready.getAcquire(entry);
        }

        Texture image() {
            if (appearance != null || !isReady()) return null;
            Texture image = texture.getTexture();
            return image == null || !image.isReady() ? null : image;
        }

        int crop() { return root == GoreState.HEAD ? 58 : 96; }
    }

    private static final class Appearance implements IHumanVisual {
        final boolean female, zombie;
        final HumanVisual visual = new HumanVisual(this);
        final ItemVisuals clothing = new ItemVisuals();

        Appearance(IHumanVisual source) {
            female = source.isFemale();
            zombie = source.isZombie();
            visual.copyFrom(source.getHumanVisual());
            for (ItemVisual item : worn) {
                if (item != null) clothing.add(new ItemVisual(item));
            }
        }

        public HumanVisual getHumanVisual() { return visual; }
        public void getItemVisuals(ItemVisuals items) {
            items.clear();
            items.addAll(clothing);
        }
        public boolean isFemale() { return female; }
        public boolean isZombie() { return zombie; }
        public boolean isSkeleton() { return false; }
    }

    static Part request(IsoGameCharacter victim, int root, boolean shortened) {
        if (pending != null || inFlight != null || GoreCore.clock() < nextBake || !GoreModel.wanted
            || !ModelManager.instance.isCreated() || !(victim instanceof IHumanVisual human) || human.isSkeleton()) return null;
        try {
            HumanVisual visual = human.getHumanVisual();
            var model = visual == null ? null : visual.getModel();
            if (model == null || !model.isReady() || !GoreModel.stockBody(model.mesh)) return null;
            for (int i = 0; i < pool.length; i++) {
                Part part = pool[i];
                if (part != null && (part.inUse || (part.texture != null && !part.isReady()))) continue;
                human.getItemVisuals(worn);
                if (worn.size() > 24) return null;
                if (part == null) pool[i] = part = new Part(i);
                part.appearance = new Appearance(human);
                part.root = root;
                part.omit = shortened ? root << 1 : 0;
                part.inUse = true;
                return pending = part;
            }
        } catch (RuntimeException ignored) {
        } finally {
            worn.clear();
        }
        return null;
    }

    static void release(Part part) {
        if (part == null) return;
        part.appearance = null;
        part.inUse = false;
        if (pending == part) pending = null;
    }

    static boolean hasWork() { return pending != null || inFlight != null; }

    static void bake() {
        if (inFlight != null && (inFlight.isReady() || GoreCore.clock() >= bakeDeadline)) inFlight = null;
        Part part = pending;
        if (part == null) return;
        pending = null;
        float now = GoreCore.clock();
        nextBake = now + 0.35f;
        try {
            Access access = Access.INSTANCE;
            if (access == null || !boundsHook || !GoreCore.dismemberLook || ModelManager.instance.bitmap == null) return;
            params.init(part.appearance, IsoDirections.SE, "zombie", "idle", 0f);
            params.variables.put(SLOT, Integer.toString(part.slot));
            params.variables.put(ROOT, Integer.toString(part.root));
            if (part.omit != 0) params.variables.put(OMIT, Integer.toString(part.omit));

            DeadBodyAtlas atlas = DeadBodyAtlas.instance;
            if (part.texture == null) {
                part.texture = atlas.getBodyTexture(params);
            } else {
                Object entry = access.entry.get(part.texture);
                Object job = access.getJob.invoke();
                access.initJob.invoke(job, params, entry);
                access.clearSlot.set(job, true);
                access.ready.setRelease(entry, false);
                @SuppressWarnings("unchecked")
                ArrayList<Object> renderJobs = (ArrayList<Object>) access.jobs.get(atlas);
                renderJobs.add(job);
            }
            inFlight = part;
            bakeDeadline = now + 2f;
        } catch (Throwable failure) {
            if (failure instanceof Error && !(failure instanceof LinkageError)) throw (Error) failure;
            part.texture = null;
        } finally {
            part.appearance = null;
            params.baseVisual = null;
            params.itemVisuals.clear();
            params.variables.clear();
        }
    }

    public static void reset() {
        pending = inFlight = null;
        nextBake = 0f;
        for (int i = 0; i < pool.length; i++) {
            Part part = pool[i];
            if (part == null) continue;
            part.texture = null;
            release(part);
            pool[i] = null;
        }
        if (GoreKillCam.active()) GoreKillCam.cancel();
    }

    public static String key(Object body, String original) {
        if (!(body instanceof BodyParams p)) return original;
        String slot = p.variables.get(SLOT);
        return slot == null ? original : SLOT + slot;
    }

    public static GoreState record(Object body) {
        if (!(body instanceof BodyParams p)) return null;
        String value = p.variables.get(ROOT);
        if (value == null) return null;
        int root = Integer.parseInt(value);
        int omit = p.variables.containsKey(OMIT) ? Integer.parseInt(p.variables.get(OMIT)) : 0;
        int row = omit == 0 ? 0 : 1;
        int bit = Integer.numberOfTrailingZeros(root);
        GoreState state = records[row][bit];
        if (state == null) {
            records[row][bit] = state = new GoreState();
            state.detachedRoot = root;
            state.detachedOmit = omit;
        }
        return state;
    }

    // The job already projects the character's bones to center the atlas entry. Use only the detached subtree.
    public static boolean bounds(Object job, BodyParams p, float[] bounds) {
        String value = p.variables.get(ROOT);
        if (value == null) return false;
        Access a = Access.INSTANCE;
        if (a == null) return false;
        var model = (AnimatedModel) a.model.get(job);
        var anim = model.getAnimationPlayer();
        if (anim == null || anim.getSkinningData() == null) return false;
        int omit = p.variables.containsKey(OMIT) ? Integer.parseInt(p.variables.get(OMIT)) : 0;
        long keep = projectedBones(anim.getSkinningData(), Integer.parseInt(value), omit);
        if (keep == 0L) return false;
        Matrix4f projection = (Matrix4f) a.projection.get(job), view = (Matrix4f) a.view.get(job);
        Matrix4f matrix = projection.mul(view, (Matrix4f) a.matrix.get(job));
        int[] viewport = (int[]) a.viewport.get(job);
        viewport[0] = viewport[1] = 0;
        viewport[2] = ModelManager.instance.bitmap.getWidth();
        viewport[3] = ModelManager.instance.bitmap.getHeight();
        Vector3f point = (Vector3f) a.point.get(job);
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        while (keep != 0L) {
            int i = Long.numberOfTrailingZeros(keep);
            keep &= keep - 1;
            if (i >= anim.getModelTransformsCount()) continue;
            var m = anim.getModelTransformAt(i);
            matrix.project(m.m03, m.m13, m.m23, viewport, point);
            minX = Math.min(minX, point.x);
            minY = Math.min(minY, point.y);
            maxX = Math.max(maxX, point.x);
            maxY = Math.max(maxY, point.y);
        }
        if (!(minX <= maxX && minY <= maxY)) return false;
        bounds[0] = minX;
        bounds[1] = minY;
        bounds[2] = maxX;
        bounds[3] = maxY;
        return true;
    }

    private static long projectedBones(zombie.core.skinnedmodel.model.SkinningData rig, int root, int omit) {
        Integer from = rig.boneIndices.get(GoreModel.detachedBone(root));
        if (from == null) return 0L;
        Integer excluded = rig.boneIndices.get(GoreModel.detachedBone(omit));
        int stop = excluded == null ? -1 : excluded;
        long keep = 0L;
        for (int i = 0, n = Math.min(60, rig.numBones()); i < n; i++) {
            for (int at = i, guard = 0; at >= 0 && at != stop && guard < 60; guard++) {
                if (at == from) {
                    keep |= 1L << i;
                    break;
                }
                at = rig.getParentBoneIdx(at);
            }
        }
        return keep;
    }

    public static int dimension(BodyParams body, int size) {
        return body.variables.containsKey(SLOT) ? ATLAS_SIZE : size;
    }

    public static void enableBounds() { boundsHook = true; }

    private static final class Access {
        static final Access INSTANCE = find();
        final VarHandle entry, ready, jobs, clearSlot, model, projection, view, matrix, viewport, point;
        final MethodHandle getJob, initJob;

        private Access() throws ReflectiveOperationException {
            Class<?> atlasEntry = Class.forName(DeadBodyAtlas.class.getName() + "$AtlasEntry");
            Class<?> job = Class.forName(DeadBodyAtlas.class.getName() + "$RenderJob");
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            var jobLookup = MethodHandles.privateLookupIn(job, lookup);
            entry = MethodHandles.privateLookupIn(BodyTexture.class, lookup).findVarHandle(BodyTexture.class, "entry", atlasEntry);
            ready = MethodHandles.privateLookupIn(atlasEntry, lookup).findVarHandle(atlasEntry, "ready", boolean.class);
            jobs = MethodHandles.privateLookupIn(DeadBodyAtlas.class, lookup).findVarHandle(DeadBodyAtlas.class, "renderJobs", ArrayList.class);
            clearSlot = jobLookup.findVarHandle(job, "clearThisSlotOnly", boolean.class);
            model = jobLookup.findVarHandle(job, "animatedModel", AnimatedModel.class);
            projection = jobLookup.findVarHandle(job, "projection", Matrix4f.class);
            view = jobLookup.findVarHandle(job, "modelView", Matrix4f.class);
            matrix = jobLookup.findVarHandle(job, "matri4f", Matrix4f.class);
            viewport = jobLookup.findVarHandle(job, "viewport", int[].class);
            point = jobLookup.findVarHandle(job, "scenePos", Vector3f.class);
            getJob = jobLookup.findStatic(job, "getNew", MethodType.methodType(job));
            initJob = jobLookup.findVirtual(job, "init", MethodType.methodType(job, BodyParams.class, atlasEntry));
        }

        private static Access find() {
            try {
                return new Access();
            } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
                return null;
            }
        }
    }
}
