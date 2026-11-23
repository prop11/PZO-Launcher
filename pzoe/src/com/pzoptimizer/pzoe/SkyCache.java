package com.pzoptimizer.pzoe;

import com.pzoptimizer.PZOLogger;
import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import zombie.core.textures.TextureDraw;

/**
 * Sun shadows and cloud shadows worked out in world space, once, instead of on every screen pixel every frame.
 *
 * The chunk shader used to walk the square grid toward the sun (up to 14 height reads), test a dozen tree crowns with
 * their leaf noise, and build the cloud field out of 3 gradient and 4 cell noises, for every pixel of every chunk draw,
 * and chunk draws overlap. None of that depends on the screen: only on the world position, the sun and the map. So it
 * goes into two textures pinned to the world, wrapped by world position like the light map (moving needs no copy):
 *
 * Sun: per player, 8 texels a square (4 on integrated graphics) over the whole loaded area, levels 0-3 stacked. Each
 * texel holds how shaded it is at 4 heights in its level (0, 1/3, 2/3, 1), so walls and props get the right shadow
 * up their side; one walk toward the sun serves all 4. A slice is redone each frame and the whole texture about once
 * a second, which follows the sun, doors, new walls and chunks coming in. Same walk, same soft PCSS-style edges,
 * same crowns as before; the chunk shader just reads it (one filtered read, kept inside the pixel's own square).
 *
 * Clouds: 2 texels a square over the loaded area (1 on integrated graphics), every 4th row of the Perlin-Worley field
 * redone each frame in turn, so it drifts and evolves as before for a few hundredths of a millisecond.
 *
 * Fog: the ground fog's drifting density noise (two value-noise octaves, 8 hashes) was rebuilt at every step of its
 * march for every pixel. It's the same field for everyone, so it's worked out here at 2 texels a square for 4 heights
 * through the fog layer, half the rows each frame, and the march reads it once a step.
 *
 * Ground snow: the lie of the snow (three turned value-noise octaves) was worked out three times per snowy pixel for
 * its relief, about 45 hashes. It only depends on where in the world it is, so ground level gets it here with its
 * slope and the ripple noise, at the sun cache's detail, and the snow reads it once. A slow rolling refresh is enough.
 *
 * Nothing runs unless the chunk shader asks for PZOSunCache / PZOCloudCache / PZOFogCache / PZOSnowCache, and the sun
 * part only while the sun is up.
 */
final class SkyCache {

    static final int LEVELS = 4, PERIOD_FRAMES = 96;
    // Clouds and fog drift with the wind, well under a texel a frame, so each frame redoes every 4th (clouds) or 2nd
    // (fog) row in turn; integrated graphics get 1 texel a square instead of 2
    static final int CLOUD_INTERLEAVE = 4, FOG_INTERLEAVE = 2;
    private static int phase;

    // Set from the chunk shader's uniform lookups (render thread), read on the main thread
    static volatile boolean sunWanted, cloudWanted, fogWanted, snowWanted;
    // Cached fog: the chunk shader's whole ground fog march per world point (PZOFogScatter0/1)
    static volatile boolean scatterWanted;
    // Fog march steps (Fog quality) and the march's strengths, set on the main thread before frame()
    static volatile int scatterSamples = 6;
    private static volatile float scFogK, scGlowK, scFogH = 1f, scSunK;
    // Sun through windows: indoor texels walk the rooms map (PixelLight.roomTexture) instead of the solid heights
    static volatile boolean roomsWanted;
    // Texels a square for the sun cache; Lua sends 4 on integrated graphics
    static volatile int detail = 8;

    // Render thread
    private static final int[] sunTex = new int[4], sunRing = new int[4], sunDetail = new int[4], cursor = new int[4];
    private static final boolean[] sunReady = new boolean[4];
    // The rooms map that was bound for the pass in progress, and whether a whole pass has used it
    private static final int[] roomPassTex = new int[4];
    private static final boolean[] roomPassDone = new boolean[4];
    private static final int[] cloudTex = new int[4], cloudRing = new int[4], cloudT = new int[4], fogT = new int[4];
    private static final boolean[] cloudReady = new boolean[4];
    private static final int[] fogTex = new int[4], fogRing = new int[4];
    private static final boolean[] fogReady = new boolean[4];
    private static final int[] snowTex = new int[4], snowRing = new int[4], snowT = new int[4], snowCursor = new int[4];
    private static final boolean[] snowReady = new boolean[4];
    private static final int[] scatTex0 = new int[4], scatTex1 = new int[4], scatRing = new int[4], scatT = new int[4];
    private static final boolean[] scatReady = new boolean[4];
    static final int SCATTER_INTERLEAVE = 2;
    private static int scatterProg, lxLight, lxFogN, lxSunC, lxField, lxInfo, lxOrigin, lxFog, lxSunInfo, lxFieldInfo, lxPhase;
    private static int sunProg, cloudProg, fogProg, snowProg, fbo, vao;
    private static int lnInfo, lnOrigin;
    private static int lsHeight, lsRooms, lsRoomsOn, lsTrees, lsTreeProfiles, lsTreeReady, lsInfo, lsOrigin, lsSun;
    private static int lcInfo, lcOrigin, lcCloud, lcPhase, lfInfo, lfOrigin, lfDrift, lfPhase;
    private static boolean failed;
    static volatile long sunTexels, cloudTexels, fogTexels, snowTexels, scatterTexels;

    /** Main thread: the ground fog march's strengths as the chunk shader will have them this frame. */
    static void scatterParams(float fogK, float glowK, float fogH, float sunK) {
        scFogK = fogK;
        scGlowK = glowK;
        scFogH = fogH;
        scSunK = sunK;
    }

    // The highest point the cache covers: above it a pixel is on level 1 to the chunk shader (its ground is the floor)
    static float scatterTop(float fogH) {
        return Math.min(Math.max(fogH, 0.5f), 0.97f);
    }

    static int scatterTexture(int p, int k) {
        return p >= 0 && p < 4 && scatReady[p] ? (k == 0 ? scatTex0[p] : scatTex1[p]) : 0;
    }

    static int scatterRing(int p) {
        return p >= 0 && p < 4 ? scatRing[p] : 0;
    }

    private SkyCache() {}

    // Render thread, for the chunk draws
    static int sunTexture(int p) {
        return p >= 0 && p < 4 && sunReady[p] ? sunTex[p] : 0;
    }

    static int sunRing(int p) {
        return p >= 0 && p < 4 ? sunRing[p] : 0;
    }

    static int sunDetail(int p) {
        return p >= 0 && p < 4 ? sunDetail[p] : 0;
    }

    static int cloudTexture(int p) {
        return p >= 0 && p < 4 && cloudReady[p] ? cloudTex[p] : 0;
    }

    static int fogTexture(int p) {
        return p >= 0 && p < 4 && fogReady[p] ? fogTex[p] : 0;
    }

    static int fogRing(int p) {
        return p >= 0 && p < 4 ? fogRing[p] : 0;
    }

    static int snowTexture(int p) {
        return p >= 0 && p < 4 && snowReady[p] ? snowTex[p] : 0;
    }

    static int snowRing(int p) {
        return p >= 0 && p < 4 ? snowRing[p] : 0;
    }

    /** Main thread, once a frame after the sun and clouds are worked out. */
    static void frame(boolean sunUp, float sunX, float sunY, float sunRise, float leaf, float cover, float cloudOffX, float cloudOffY, float clock,
                      boolean fogUp, float driftX, float driftY, boolean snowUp) {
        if (failed) return;
        boolean doSun = sunWanted && sunUp;
        boolean doCloud = cloudWanted && cover > 0f;
        // Cached fog also carries the light glow, which shows without any fog, so the noise it reads keeps going
        boolean doFog = fogWanted && (fogUp || (scatterWanted && scGlowK > 0f));
        boolean doSnow = snowWanted && snowUp;
        if (!doSun && !doCloud && !doFog && !doSnow) return;
        zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance == null ? null : zombie.iso.IsoWorld.instance.currentCell;
        if (cell == null) return;
        int[] ox = new int[4], oy = new int[4], ring = new int[4], height = new int[4], rooms = new int[4], light = new int[4];
        boolean any = false;
        for (int p = 0; p < 4; p++) {
            zombie.iso.IsoChunkMap map = cell.getChunkMap(p);
            int r = PixelLight.lightRing(p);
            int h = PixelLight.heightTexture(p);
            if (map == null || r <= 0 || (doSun && h == 0 && !doCloud && !doFog && !doSnow)) continue;
            ox[p] = map.getWorldXMinTiles();
            oy[p] = map.getWorldYMinTiles();
            ring[p] = r;
            height[p] = h;
            rooms[p] = roomsWanted ? PixelLight.roomTexture(p) : 0;
            light[p] = PixelLight.lightTexture(p);
            any = true;
        }
        if (!any) return;
        zombie.core.SpriteRenderer.instance.drawGeneric(new Step(doSun, doCloud, doFog, doSnow, ox, oy, ring, height, rooms, light, detail, sunX, sunY, sunRise, leaf,
            cover, cloudOffX, cloudOffY, clock, driftX, driftY));
    }

    private static final class Step extends TextureDraw.GenericDrawer {
        final boolean doSun, doCloud, doFog, doSnow;
        final int[] ox, oy, ring, height, rooms, light;
        // Cached fog: on, steps, strengths (fogK, glowK, fogH, sunK), moving fog field (texture, corner, ready)
        final boolean doScatter;
        final int samples, fieldTex;
        final float fogK, glowK, fogH, sunK, fieldOx, fieldOy;
        final int detail;
        final float sunX, sunY, sunRise, leaf, cover, cloudOffX, cloudOffY, clock, driftX, driftY;

        Step(boolean doSun, boolean doCloud, boolean doFog, boolean doSnow, int[] ox, int[] oy, int[] ring, int[] height, int[] rooms, int[] light, int detail, float sunX, float sunY,
             float sunRise, float leaf, float cover, float cloudOffX, float cloudOffY, float clock, float driftX, float driftY) {
            this.doSun = doSun;
            this.doCloud = doCloud;
            this.doFog = doFog;
            this.doSnow = doSnow;
            this.driftX = driftX;
            this.driftY = driftY;
            this.ox = ox;
            this.oy = oy;
            this.ring = ring;
            this.height = height;
            this.rooms = rooms;
            this.light = light;
            this.doScatter = doFog && scatterWanted && scFogK + scGlowK > 0f;
            this.samples = Math.max(4, Math.min(8, scatterSamples));
            this.fogK = scFogK;
            this.glowK = scGlowK;
            this.fogH = scFogH;
            this.sunK = scSunK;
            this.fieldTex = FogField.wanted ? FogField.texture() : 0;
            this.fieldOx = FogField.fieldOx;
            this.fieldOy = FogField.fieldOy;
            this.detail = detail;
            this.sunX = sunX;
            this.sunY = sunY;
            this.sunRise = sunRise;
            this.leaf = leaf;
            this.cover = cover;
            this.cloudOffX = cloudOffX;
            this.cloudOffY = cloudOffY;
            this.clock = clock;
        }

        @Override
        public void render() {
            try {
                run(this);
            } catch (Throwable t) {
                failed = true;
                PZOLogger.warn("[SkyCache] off for this session: " + t);
            }
        }
    }

    private static void run(Step s) {
        int prevFbo = GlGet.integer(GL30.GL_FRAMEBUFFER_BINDING);
        int prevProgram = GlGet.integer(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GlGet.integer(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevActive = GlGet.integer(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE8);
        int prev8 = GlGet.integer(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE9);
        int prev9 = GlGet.integer(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE6);
        int prev6 = GlGet.integer(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE7);
        int prev7 = GlGet.integer(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glPushAttrib(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_ENABLE_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_VIEWPORT_BIT
            | GL11.GL_SCISSOR_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        try {
            if (sunProg == 0 && !init()) {
                failed = true;
                return;
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glColorMask(true, true, true, true);
            GL30.glBindVertexArray(vao);
            phase++;
            for (int p = 0; p < 4; p++) {
                if (s.ring[p] <= 0) continue;
                if (s.doSun && s.height[p] != 0) sunBand(s, p);
                if (s.doCloud) clouds(s, p);
                if (s.doFog) fog(s, p);
                if (s.doScatter && scatterProg != 0 && fogReady[p] && s.light[p] != 0) scatter(s, p);
                else scatReady[p] = false;
                if (s.doSnow) snowBand(s, p);
            }
        } finally {
            GL30.glBindVertexArray(prevVao);
            GL20.glUseProgram(prevProgram);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
            GL11.glPopAttrib();
            GL13.glActiveTexture(GL13.GL_TEXTURE8);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev8);
            GL13.glActiveTexture(GL13.GL_TEXTURE9);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev9);
            GL13.glActiveTexture(GL13.GL_TEXTURE6);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev6);
            GL13.glActiveTexture(GL13.GL_TEXTURE7);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev7);
            GL13.glActiveTexture(prevActive);
        }
    }

    private static void sunBand(Step s, int p) {
        int ring = s.ring[p], t = Math.max(2, Math.min(8, s.detail));
        int w = ring * t, rows = w * LEVELS;
        if (sunTex[p] == 0 || sunRing[p] != ring || sunDetail[p] != t) {
            if (sunTex[p] != 0) GL11.glDeleteTextures(sunTex[p]);
            sunTex[p] = texture(w, rows, GL11.GL_LINEAR, GL12.GL_CLAMP_TO_EDGE);
            sunRing[p] = ring;
            sunDetail[p] = t;
            sunReady[p] = false;
            cursor[p] = 0;
        }
        int band = (rows + PERIOD_FRAMES - 1) / PERIOD_FRAMES;
        // Before the first full pass the texture holds nothing useful, so it fills four times as fast
        if (!sunReady[p]) band *= 4;
        int y0 = cursor[p], h = Math.min(band, rows - y0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, sunTex[p], 0);
        GL11.glViewport(0, y0, w, h);
        GL20.glUseProgram(sunProg);
        GL13.glActiveTexture(GL13.GL_TEXTURE8);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, s.height[p]);
        GL20.glUniform1i(lsHeight, 8);
        int rt = s.rooms[p];
        if (rt != roomPassTex[p]) {
            // A new rooms map (or none): indoor texels count as worked out once a whole pass has read it
            roomPassTex[p] = rt;
            roomPassDone[p] = false;
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE9);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, rt);
        GL20.glUniform1i(lsRooms, 9);
        GL20.glUniform1f(lsRoomsOn, rt != 0 ? 1f : 0f);
        int trees = PixelLight.treeTexture(p);
        GL13.glActiveTexture(GL13.GL_TEXTURE6);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, trees);
        GL20.glUniform1i(lsTrees, 6);
        GL13.glActiveTexture(GL13.GL_TEXTURE7);
        GL20.glUniform1i(lsTreeProfiles, 7);
        GL20.glUniform1i(lsTreeReady, trees != 0 ? TreeShadowProfiles.bind() : 0);
        GL20.glUniform4f(lsInfo, ring, t, w, LEVELS);
        GL20.glUniform4f(lsOrigin, s.ox[p], s.oy[p], 0f, 0f);
        GL20.glUniform4f(lsSun, s.sunX, s.sunY, s.sunRise, s.leaf);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        sunTexels += (long) w * h;
        y0 += h;
        if (y0 >= rows) {
            y0 = 0;
            sunReady[p] = true;
            if (rt != 0) roomPassDone[p] = true;
        }
        cursor[p] = y0;
    }

    /** Render thread: the sun cache's indoor texels hold sun through windows for this player. */
    static boolean roomsReady(int p) {
        return p >= 0 && p < 4 && roomPassDone[p] && roomPassTex[p] != 0;
    }

    static int fieldTexels(int detail) {
        return detail >= 8 ? 2 : 1;
    }

    private static void clouds(Step s, int p) {
        int ring = s.ring[p], t = fieldTexels(s.detail), w = ring * t;
        boolean fresh = cloudTex[p] == 0 || cloudRing[p] != ring || cloudT[p] != t;
        if (fresh) {
            if (cloudTex[p] != 0) GL11.glDeleteTextures(cloudTex[p]);
            // Repeat: the field is continuous across the wrap inside the loaded area, so filtering may cross it
            cloudTex[p] = texture(w, w, GL11.GL_LINEAR, GL11.GL_REPEAT);
            cloudRing[p] = ring;
            cloudT[p] = t;
            cloudReady[p] = false;
        }
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, cloudTex[p], 0);
        GL11.glViewport(0, 0, w, w);
        GL20.glUseProgram(cloudProg);
        GL20.glUniform4f(lcInfo, ring, t, s.cover, s.clock);
        GL20.glUniform4f(lcOrigin, s.ox[p], s.oy[p], 0f, 0f);
        GL20.glUniform4f(lcCloud, s.cloudOffX, s.cloudOffY, 0f, 0f);
        // A new texture is filled whole first
        int every = cloudReady[p] ? CLOUD_INTERLEAVE : 1;
        GL20.glUniform2f(lcPhase, phase % every, every);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        cloudTexels += (long) w * w / every;
        cloudReady[p] = true;
    }

    private static void fog(Step s, int p) {
        int ring = s.ring[p], t = fieldTexels(s.detail), w = ring * t;
        if (fogTex[p] == 0 || fogRing[p] != ring || fogT[p] != t) {
            if (fogTex[p] != 0) GL11.glDeleteTextures(fogTex[p]);
            fogTex[p] = texture(w, w, GL11.GL_LINEAR, GL11.GL_REPEAT);
            fogRing[p] = ring;
            fogT[p] = t;
            fogReady[p] = false;
        }
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, fogTex[p], 0);
        GL11.glViewport(0, 0, w, w);
        GL20.glUseProgram(fogProg);
        GL20.glUniform4f(lfInfo, ring, t, FOG_LAYER_TOP, 0f);
        GL20.glUniform4f(lfOrigin, s.ox[p], s.oy[p], 0f, 0f);
        GL20.glUniform4f(lfDrift, s.driftX, s.driftY, 0f, 0f);
        int every = fogReady[p] ? FOG_INTERLEAVE : 1;
        GL20.glUniform2f(lfPhase, phase % every, every);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        fogTexels += (long) w * w / every;
        fogReady[p] = true;
    }

    private static void scatter(Step s, int p) {
        int ring = s.ring[p], t = fieldTexels(s.detail), w = ring * t;
        if (scatTex0[p] == 0 || scatRing[p] != ring || scatT[p] != t) {
            if (scatTex0[p] != 0) GL11.glDeleteTextures(scatTex0[p]);
            if (scatTex1[p] != 0) GL11.glDeleteTextures(scatTex1[p]);
            // Half floats: the glow adds up past 1
            scatTex0[p] = floatTexture(w, w);
            scatTex1[p] = floatTexture(w, w);
            scatRing[p] = ring;
            scatT[p] = t;
            scatReady[p] = false;
        }
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, scatTex0[p], 0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL11.GL_TEXTURE_2D, scatTex1[p], 0);
        GL20.glDrawBuffers(new int[] { GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1 });
        try {
            GL11.glViewport(0, 0, w, w);
            GL20.glUseProgram(scatterProg);
            GL13.glActiveTexture(GL13.GL_TEXTURE6);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, s.light[p]);
            GL13.glActiveTexture(GL13.GL_TEXTURE7);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, fogTex[p]);
            GL13.glActiveTexture(GL13.GL_TEXTURE8);
            int st = sunReady[p] && sunRing[p] == ring ? sunTex[p] : 0;
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, st);
            GL13.glActiveTexture(GL13.GL_TEXTURE9);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, s.fieldTex);
            GL20.glUniform1i(lxLight, 6);
            GL20.glUniform1i(lxFogN, 7);
            GL20.glUniform1i(lxSunC, 8);
            GL20.glUniform1i(lxField, 9);
            GL20.glUniform4f(lxInfo, ring, t, s.samples, FOG_LAYER_TOP);
            GL20.glUniform4f(lxOrigin, s.ox[p], s.oy[p], PixelLight.BASE_LEVEL, PixelLight.LEVELS);
            GL20.glUniform4f(lxFog, s.fogK, s.glowK, s.fogH, st != 0 ? s.sunK : 0f);
            GL20.glUniform4f(lxSunInfo, ring, Math.max(1, sunDetail[p]), st != 0 ? 1f : 0f, LEVELS);
            GL20.glUniform4f(lxFieldInfo, s.fieldOx, s.fieldOy, s.fieldTex != 0 ? 1f : 0f, FogField.HEIGHT);
            int every = scatReady[p] ? SCATTER_INTERLEAVE : 1;
            GL20.glUniform2f(lxPhase, phase % every, every);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            scatterTexels += (long) w * w / every;
            scatReady[p] = true;
        } finally {
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL11.GL_TEXTURE_2D, 0, 0);
            GL20.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);
        }
    }

    private static int floatTexture(int w, int h) {
        GL13.glActiveTexture(GL13.GL_TEXTURE8);
        int t = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, w, h, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer) null);
        return t;
    }

    private static void snowBand(Step s, int p) {
        int ring = s.ring[p], t = Math.max(2, Math.min(8, s.detail)), w = ring * t;
        if (snowTex[p] == 0 || snowRing[p] != ring || snowT[p] != t) {
            if (snowTex[p] != 0) GL11.glDeleteTextures(snowTex[p]);
            snowTex[p] = texture(w, w, GL11.GL_LINEAR, GL11.GL_REPEAT);
            snowRing[p] = ring;
            snowT[p] = t;
            snowReady[p] = false;
            snowCursor[p] = 0;
        }
        // The field never changes; the rolling pass only follows the loaded area moving. Filled whole the first time.
        int band = snowReady[p] ? (w + PERIOD_FRAMES - 1) / PERIOD_FRAMES : w;
        int y0 = snowCursor[p], h = Math.min(band, w - y0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, snowTex[p], 0);
        GL11.glViewport(0, y0, w, h);
        GL20.glUseProgram(snowProg);
        GL20.glUniform4f(lnInfo, ring, t, 0f, 0f);
        GL20.glUniform4f(lnOrigin, s.ox[p], s.oy[p], 0f, 0f);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        snowTexels += (long) w * h;
        y0 += h;
        if (y0 >= w) {
            y0 = 0;
            snowReady[p] = true;
        }
        snowCursor[p] = y0;
    }

    // Highest fog the card allows, in levels (Fog height 200 percent); the 4 stored heights spread up to it
    static final float FOG_LAYER_TOP = 0.66f;

    private static int texture(int w, int h, int filter, int wrap) {
        GL13.glActiveTexture(GL13.GL_TEXTURE8);
        int t = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, wrap);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, wrap);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        return t;
    }

    private static boolean init() {
        sunProg = compile(VERTEX, SUN_FRAGMENT, "sun");
        cloudProg = compile(VERTEX, CLOUD_FRAGMENT, "cloud");
        fogProg = compile(VERTEX, FOG_FRAGMENT, "fog");
        scatterProg = compile(VERTEX, SCATTER_FRAGMENT, "fog scatter");
        snowProg = compile(VERTEX, SNOW_FRAGMENT, "snow");
        if (sunProg == 0 || cloudProg == 0 || fogProg == 0 || snowProg == 0) return false;
        lnInfo = GL20.glGetUniformLocation(snowProg, "uInfo");
        lnOrigin = GL20.glGetUniformLocation(snowProg, "uOrigin");
        lfInfo = GL20.glGetUniformLocation(fogProg, "uInfo");
        if (scatterProg != 0) {
            lxLight = GL20.glGetUniformLocation(scatterProg, "uLight");
            lxFogN = GL20.glGetUniformLocation(scatterProg, "uFogN");
            lxSunC = GL20.glGetUniformLocation(scatterProg, "uSunC");
            lxField = GL20.glGetUniformLocation(scatterProg, "uField");
            lxInfo = GL20.glGetUniformLocation(scatterProg, "uInfo");
            lxOrigin = GL20.glGetUniformLocation(scatterProg, "uOrigin");
            lxFog = GL20.glGetUniformLocation(scatterProg, "uFog");
            lxSunInfo = GL20.glGetUniformLocation(scatterProg, "uSunInfo");
            lxFieldInfo = GL20.glGetUniformLocation(scatterProg, "uFieldInfo");
            lxPhase = GL20.glGetUniformLocation(scatterProg, "uPhase");
        }
        lfOrigin = GL20.glGetUniformLocation(fogProg, "uOrigin");
        lfDrift = GL20.glGetUniformLocation(fogProg, "uDrift");
        lfPhase = GL20.glGetUniformLocation(fogProg, "uPhase");
        lcPhase = GL20.glGetUniformLocation(cloudProg, "uPhase");
        lsHeight = GL20.glGetUniformLocation(sunProg, "uHeight");
        lsTrees = GL20.glGetUniformLocation(sunProg, "uTrees");
        lsTreeProfiles = GL20.glGetUniformLocation(sunProg, "uTreeProfiles");
        lsTreeReady = GL20.glGetUniformLocation(sunProg, "uTreeReady");
        lsRooms = GL20.glGetUniformLocation(sunProg, "uRooms");
        lsRoomsOn = GL20.glGetUniformLocation(sunProg, "uRoomsOn");
        lsInfo = GL20.glGetUniformLocation(sunProg, "uInfo");
        lsOrigin = GL20.glGetUniformLocation(sunProg, "uOrigin");
        lsSun = GL20.glGetUniformLocation(sunProg, "uSun");
        lcInfo = GL20.glGetUniformLocation(cloudProg, "uInfo");
        lcOrigin = GL20.glGetUniformLocation(cloudProg, "uOrigin");
        lcCloud = GL20.glGetUniformLocation(cloudProg, "uCloud");
        fbo = GL30.glGenFramebuffers();
        vao = GL30.glGenVertexArrays();
        return true;
    }

    static int compile(String vs, String fs, String what) {
        int p = GL20.glCreateProgram();
        for (int k = 0; k < 2; k++) {
            int s = GL20.glCreateShader(k == 0 ? GL20.GL_VERTEX_SHADER : GL20.GL_FRAGMENT_SHADER);
            GL20.glShaderSource(s, k == 0 ? vs : fs);
            GL20.glCompileShader(s);
            if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) {
                PZOLogger.warn("[SkyCache] " + what + " shader didn't compile: " + GL20.glGetShaderInfoLog(s, 4096).trim());
                GL20.glDeleteShader(s);
                GL20.glDeleteProgram(p);
                return 0;
            }
            GL20.glAttachShader(p, s);
            GL20.glDeleteShader(s);
        }
        GL20.glLinkProgram(p);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
            PZOLogger.warn("[SkyCache] " + what + " shader didn't link: " + GL20.glGetProgramInfoLog(p, 4096).trim());
            GL20.glDeleteProgram(p);
            return 0;
        }
        return p;
    }

    static final String VERTEX = "#version 330\nvoid main()\n{\n"
        + "    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));\n"
        + "    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n}\n";

    // One texel = one point of the world at 4 heights in its level. uInfo = ring squares, texels a square, texels per
    // level slice, levels; uOrigin.xy = the loaded area's lowest square (gives each wrapped texel its real square);
    // uSun = toward the sun, levels climbed per square, leaf 0..1. Out: how shaded at heights 0, 1/3, 2/3, 1.
    static final String SUN_FRAGMENT = """
        #version 330
        uniform sampler2D uHeight;
        uniform sampler2D uTrees;
        uniform sampler2D uTreeProfiles;
        uniform int uTreeReady;
        // Rooms map (PixelLight.rooms): R = indoors per level bit, G / B = north / west edge openings (window bits
        // 0-3, open door bits 4-7), A = ground floor furniture height. uRoomsOn 0 = none bound.
        uniform sampler2D uRooms;
        uniform float uRoomsOn;
        uniform vec4 uInfo;
        uniform vec4 uOrigin;
        uniform vec4 uSun;
        out vec4 o;

        vec4 roomAt(vec2 sq)
        {
            return floor(texture(uRooms, (mod(sq, uInfo.x) + 0.5) / uInfo.x) * 255.0 + 0.5);
        }

        float bit(float v, float b)
        {
            return mod(floor(v / exp2(b)), 2.0);
        }

        // How open an edge is at heights rz on level L: a window from a third of the way up to near the top, an
        // open door from the floor
        vec4 opening(float e, float L, vec4 rz)
        {
            vec4 f = rz - L;
            vec4 top = 1.0 - smoothstep(0.8, 0.86, f);
            vec4 o = bit(e, L) > 0.5 ? smoothstep(0.28, 0.34, f) * top : vec4(0.0);
            return bit(e, L + 4.0) > 0.5 ? max(o, top) : o;
        }

        // Heights in levels (content, north wall, west wall) and opacity of a square; low bits are flags (G, B =
        // see-through wall), same packing as PixelLight.heights
        vec4 heightSee(vec2 sq, out vec2 see)
        {
            vec4 t = texture(uHeight, (mod(sq, uInfo.x) + 0.5) / uInfo.x);
            vec4 r = floor(t * 255.0 + 0.5);
            vec4 bits = mod(r, 2.0);
            see = bits.gb;
            r -= bits;
            return vec4(r.rgb / 32.0, r.a / 254.0);
        }

        vec4 heightAt(vec2 sq)
        {
            vec2 see;
            return heightSee(sq, see);
        }

        float least(vec4 v)
        {
            return min(min(v.x, v.y), min(v.z, v.w));
        }

        // One sun-facing projection for the trunk and crown. Each height samples the same sprite profile.
        float treeCoverage(vec2 tile, float x, float y, float blur, float leaf)
        {
            if (y < 0.0 || y > 1.0) return 0.0;
            vec2 uv = clamp(vec2(x, y) * vec2(32.0, 64.0), vec2(0.5), vec2(31.5, 63.5));
            vec4 mask = texture(uTreeProfiles, (tile + uv) / vec2(256.0, 2048.0));
            vec2 opacity = mix(mask.rg, mask.ba, blur);
            return 1.0 - (1.0 - opacity.r * 0.88) * (1.0 - opacity.g * leaf * 0.72);
        }

        vec4 treeShadow(vec2 w, vec4 z, vec2 sq)
        {
            if (any(lessThan(sq, uOrigin.xy)) || any(greaterThanEqual(sq, uOrigin.xy + uInfo.x))) return vec4(0.0);
            vec4 d = floor(texture(uTrees, (mod(sq, uInfo.x) + 0.5) / uInfo.x) * 255.0 + 0.5);
            if (d.r < 0.5) return vec4(0.0);
            vec2 rel = sq + 0.5 - w;
            float along = dot(rel, uSun.xy);
            if (along < -0.12 || along >= 11.0) return vec4(0.0);
            float radius = mod(d.a, 128.0) / 32.0;
            float across = dot(rel, vec2(-uSun.y, uSun.x));
            if (abs(across) >= radius) return vec4(0.0);
            float height = d.b / 32.0;
            vec4 ry = (z + max(along, 0.0) * uSun.z - (d.g - 1.0)) / height;
            if (ry.w < 0.0 || ry.x > 1.0) return vec4(0.0);
            float slot = d.r <= float(uTreeReady) ? d.r : (d.a >= 128.0 ? 2.0 : 1.0);
            vec2 tile = vec2(mod(slot, 8.0) * 32.0, floor(slot / 8.0) * 64.0);
            float x = across / (-2.0 * radius) + 0.5;
            float blur = smoothstep(0.35, 6.0, along);
            float leaf = d.a >= 128.0 ? 1.0 : uSun.w;
            return vec4(treeCoverage(tile, x, ry.x, blur, leaf), treeCoverage(tile, x, ry.y, blur, leaf),
                treeCoverage(tile, x, ry.z, blur, leaf), treeCoverage(tile, x, ry.w, blur, leaf))
                * (1.0 - smoothstep(9.0, 11.0, along));
        }

        // The ray toward the sun walks the square grid exactly (every square it crosses, in order, one read each), the
        // penumbra widening with the distance from what casts it, at the far end (height) and along the sides (how
        // deep the ray cuts a square). The texel's own square doesn't shade it; its walls do, as edges.
        // room: the texel is indoors on level base. Then a room the ray is in has a ceiling at the top of its level and
        // only its ground floor furniture stands in the way (the heights count upper floors as solid), and windows and
        // open doors in its walls let the sun through. Out of the window it's the outdoor walk again.
        vec4 sunShadow(vec2 w, vec4 z, float base, bool room)
        {
            vec2 own = floor(w);
            vec2 dir = uSun.xy;
            dir = vec2(abs(dir.x) < 0.0001 ? 0.0001 : dir.x, abs(dir.y) < 0.0001 ? 0.0001 : dir.y);
            vec2 stp = sign(dir);
            vec2 inv = 1.0 / abs(dir);
            vec2 cell = own;
            vec2 tNext = (stp * (cell - w) + max(stp, 0.0)) * inv;
            float t = 0.0;
            vec2 see;
            vec4 h = heightSee(cell, see);
            vec4 occ = vec4(0.0);
            // How far the ray has run through solid squares in a row, per height. Only the outer edge of a block,
            // where the ray just clips a corner, softens; judged per square, every square boundary inside a
            // building cut the run in two and the shadow's side came out as a saw tooth.
            vec4 run = vec4(0.0);
            float tMax = min(11.0, 3.0 / uSun.z);
            vec4 rm = room ? roomAt(cell) : vec4(0.0);
            for (int i = 0; i < 24; i++) {
                float tExit = min(tNext.x, tNext.y);
                float soft = 0.06 + 0.06 * t;
                if (room && bit(rm.r, base) > 0.5) {
                    run = vec4(0.0);
                    occ = max(occ, smoothstep(base + 0.96, base + 1.01, z + tExit * uSun.z));
                    if (cell != own && base < 0.5 && rm.a > 0.0) {
                        vec4 rz = z + t * uSun.z;
                        float miss = length(w + dir * clamp(dot(cell + 0.5 - w, dir), t, tExit) - cell - 0.5);
                        occ = max(occ, smoothstep(0.0, soft, rm.a / 255.0 - rz) * 0.8 * (1.0 - smoothstep(0.3 - soft, 0.34 + soft, miss)));
                    }
                } else if (t < tMax) {
                    vec4 rz = z + t * uSun.z;
                    float chord = tExit - t;
                    vec2 c = cell + 0.5 - w;
                    float along = clamp(dot(c, dir), t, tExit);
                    float miss = length(w + dir * along - cell - 0.5);
                    if (h.a >= 0.99 && cell != own) {
                        vec4 k = smoothstep(0.0, soft, h.r - rz) * h.a;
                        run = (run + chord) * step(0.01, k);
                        occ = max(occ, k * smoothstep(0.0, 0.08 + 0.12 * soft, run));
                    } else if (h.a >= 0.6 && h.a < 0.99 && h.r > 0.01) {
                        run = vec4(0.0);
                        float radius = h.a < 0.72 ? 0.13 : 0.32;
                        vec4 k = smoothstep(0.0, soft, h.r - (z + along * uSun.z)) * h.a;
                        k *= 1.0 - smoothstep(max(0.01, radius - soft), radius + soft, miss);
                        occ = max(occ, k);
                    } else {
                        run = vec4(0.0);
                    }
                }
                if (tExit > tMax || least(occ) > 0.98) break;
                vec2 prev = cell;
                vec4 prevH = h;
                vec2 prevSee = see;
                vec4 prevRm = rm;
                bool xSide = tNext.x < tNext.y;
                t = tExit;
                if (xSide) {
                    cell.x += stp.x;
                    tNext.x += inv.x;
                } else {
                    cell.y += stp.y;
                    tNext.y += inv.y;
                }
                h = heightSee(cell, see);
                if (room) rm = roomAt(cell);
                // The square with the larger x owns the wall on the edge between them (west), larger y the north one.
                // See-through fences only take a little of the sun.
                vec4 rzb = z + t * uSun.z;
                float softb = 0.06 + 0.06 * t;
                if (xSide) {
                    bool mine = cell.x > prev.x;
                    vec4 open = room ? opening(mine ? rm.b : prevRm.b, base, rzb) : vec4(0.0);
                    occ = max(occ, smoothstep(0.0, softb, (mine ? h.b : prevH.b) - rzb) * (1.0 - 0.75 * (mine ? see.y : prevSee.y)) * (1.0 - open));
                } else {
                    bool mine = cell.y > prev.y;
                    vec4 open = room ? opening(mine ? rm.g : prevRm.g, base, rzb) : vec4(0.0);
                    occ = max(occ, smoothstep(0.0, softb, (mine ? h.g : prevH.g) - rzb) * (1.0 - 0.75 * (mine ? see.x : prevSee.x)) * (1.0 - open));
                }
            }
            if (least(occ) > 0.98) return occ;
            // Scan the strip that can intersect a silhouette. Consecutive neighbourhoods overlap.
            if (uTreeReady >= 2) {
                vec2 previous = vec2(-1e6);
                for (int k = 0; k <= 11; k++) {
                    vec2 center = floor(w + uSun.xy * float(k));
                    if (center == previous) continue;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            vec2 sq = center + vec2(dx, dy);
                            if (all(lessThanEqual(abs(sq - previous), vec2(1.0)))) continue;
                            occ = max(occ, treeShadow(w, z, sq));
                        }
                    }
                    previous = center;
                    if (least(occ) > 0.98) break;
                }
            }
            return occ;
        }

        void main()
        {
            vec2 fc = floor(gl_FragCoord.xy);
            float T = uInfo.y;
            float W = uInfo.z;
            float level = floor(fc.y / W);
            vec2 tx = vec2(fc.x, fc.y - level * W);
            vec2 m = floor(tx / T);
            vec2 sub = (tx - m * T + 0.5) / T;
            vec2 sq = uOrigin.xy + mod(m - uOrigin.xy, uInfo.x);
            // Upper levels are mostly open air: no floor, roof or wall there means no surface anyone sees
            if (level > 0.5) {
                vec4 hs = heightAt(sq);
                vec4 hn = heightAt(sq + vec2(0.0, 1.0)), hw = heightAt(sq + vec2(1.0, 0.0));
                float top = max(max(hs.r, max(hs.g, hs.b)), max(hn.g, hw.b));
                if (top < level - 0.05) {
                    o = vec4(0.0);
                    return;
                }
            }
            bool room = uRoomsOn > 0.5 && bit(roomAt(sq).r, level) > 0.5;
            o = sunShadow(sq + sub, level + vec4(0.0, 1.0 / 3.0, 2.0 / 3.0, 1.0), level, room);
        }
        """;

    // Cached fog: the chunk shader's ground fog march (pzoAtmosPost), done here for each world point of level 0 at two
    // heights, the ground (o0) and half way up what the cache covers (o1), instead of for every screen pixel of every
    // chunk draw. The camera never turns, so the march from a point only depends on the point. Out: rgb = light the fog
    // adds, a = how much of what's behind still shows. Same light map, fog noise cache, sun cache and moving fog field
    // the chunk shader reads, in world squares. uInfo = ring, texels a square, steps, fog noise top; uOrigin = loaded
    // area corner, light map base level, levels; uFog = fog, glow, fog height, sun (already times fog);
    // uSunInfo = sun cache ring, texels a square, ready, levels; uFieldInfo = field corner, ready, height.
    static final String SCATTER_FRAGMENT = """
        #version 330
        uniform sampler2D uLight;
        uniform sampler2D uFogN;
        uniform sampler2D uSunC;
        uniform sampler2D uField;
        uniform vec4 uInfo;
        uniform vec4 uOrigin;
        uniform vec4 uFog;
        uniform vec4 uSunInfo;
        uniform vec4 uFieldInfo;
        uniform vec2 uPhase;
        layout(location = 0) out vec4 o0;
        layout(location = 1) out vec4 o1;

        vec2 lightUV(vec3 own, vec2 f, float slice)
        {
            vec2 cell = mod(own.xy, uInfo.x);
            float lz = clamp(own.z - uOrigin.z, 0.0, uOrigin.w - 1.0);
            float tile = 2.0 * uInfo.x;
            float s = 2.0 * lz + slice;
            return (vec2(mod(s, 8.0), floor(s / 8.0)) * tile + 2.0 * cell + 0.5 + f) / (vec2(8.0, 6.0) * tile);
        }

        vec4 lightAt(vec3 p)
        {
            vec3 own = floor(p + 0.03);
            vec3 f = clamp(p - own, 0.0, 1.0);
            vec4 lo = texture(uLight, lightUV(own, f.xy, 0.0));
            if (f.z < 0.02) return lo;
            return mix(lo, texture(uLight, lightUV(own, f.xy, 1.0)), f.z);
        }

        float sunShadow(vec3 q)
        {
            if (uSunInfo.z < 0.5 || q.z < -0.02) return 0.0;
            float lv = floor(q.z + 0.03);
            if (lv >= uSunInfo.w) return 0.0;
            float T = uSunInfo.y;
            float R = uSunInfo.x;
            vec2 own = floor(q.xy + 0.03);
            vec2 qq = clamp(q.xy, own + 0.5 / T, own + 1.0 - 0.5 / T);
            vec2 tc = (own - R * floor(own / R) + (qq - own)) * T;
            vec4 c = texture(uSunC, vec2(tc.x, tc.y + lv * R * T) / vec2(R * T, R * T * uSunInfo.w));
            float e = clamp(q.z - lv, 0.0, 1.0) * 3.0;
            return e < 1.0 ? mix(c.r, c.g, e) : (e < 2.0 ? mix(c.g, c.b, e - 1.0) : mix(c.b, c.a, e - 2.0));
        }

        float fogN(vec2 wq, float h)
        {
            vec4 c = texture(uFogN, wq / uInfo.x);
            float e = clamp(h / uInfo.w, 0.0, 1.0) * 3.0;
            return e < 1.0 ? mix(c.r, c.g, e) : (e < 2.0 ? mix(c.g, c.b, e - 1.0) : mix(c.b, c.a, e - 2.0));
        }

        float field(vec2 wxy, float h, float outdoors)
        {
            vec2 g = (wxy - uFieldInfo.xy) / 64.0;
            if (uFieldInfo.z < 0.5 || g.x <= 0.0 || g.y <= 0.0 || g.x >= 1.0 || g.y >= 1.0) return outdoors;
            float lz = clamp(h / uFieldInfo.w * 4.0 - 0.5, 0.0, 3.0);
            float l0 = floor(lz);
            float l1 = min(l0 + 1.0, 3.0);
            float gx = clamp(g.x, 0.5 / 128.0, 1.0 - 0.5 / 128.0);
            float m = mix(texture(uField, vec2((gx + l0) * 0.25, g.y)).b, texture(uField, vec2((gx + l1) * 0.25, g.y)).b, lz - l0);
            vec2 e = min(g, 1.0 - g);
            return mix(outdoors, m, smoothstep(0.0, 0.06, min(e.x, e.y)));
        }

        // Ground is level 0, so a point's height is its z
        vec4 march(vec3 p)
        {
            float fogH = uFog.z;
            float span = max(fogH, 0.5) - p.z;
            if (span <= 0.0) return vec4(0.0, 0.0, 0.0, 1.0);
            int n = int(uInfo.z + 0.5);
            float dz = span / float(n);
            vec3 stepv = vec3(3.0, 3.0, 1.0) * dz;
            float len = dz * 4.9;
            vec3 q = p + stepv * 0.5;
            float T = 1.0;
            vec3 S = vec3(0.0);
            for (int i = 0; i < 8; i++) {
                if (i >= n) break;
                vec4 L = lightAt(q);
                float outdoors = step(63.5, mod(floor(L.a * 255.0 + 0.5), 128.0));
                float h = q.z;
                outdoors = field(q.xy, h, outdoors);
                float fall = clamp(1.0 - h / fogH, 0.0, 1.0);
                float fd = uFog.x * outdoors * fall * fall * (0.35 + 0.9 * fogN(q.xy, h)) * len;
                float gd = uFog.y * 0.08 * len;
                S += T * (fd * L.rgb * vec3(0.92, 0.95, 1.0) + gd * L.rgb * L.rgb);
                if (uFog.w > 0.0 && fd > 0.0) S += T * fd * uFog.w * (1.0 - sunShadow(q)) * vec3(1.0, 0.9, 0.75) * 0.35;
                T *= exp(-fd);
                q += stepv;
            }
            return vec4(S, T);
        }

        void main()
        {
            vec2 fc = floor(gl_FragCoord.xy);
            if (mod(fc.y, uPhase.y) != uPhase.x) discard;
            vec2 m = floor(fc / uInfo.y);
            vec2 sub = (fc - m * uInfo.y + 0.5) / uInfo.y;
            vec2 wq = uOrigin.xy + mod(m - uOrigin.xy, uInfo.x) + sub;
            float top = min(max(uFog.z, 0.5), 0.97);
            o0 = march(vec3(wq, 0.0));
            o1 = march(vec3(wq, 0.5 * top));
        }
        """;

    // Cloud cover over the loaded area, 2 texels a square, wrapped like the sun cache. Perlin-Worley as in Guerrilla's
    // Nubis (Schneider 2015): the fbm says where the clouds are, the cells round them into billows and finer cells
    // erode the edges into cauliflower; domain warped so the billows curl, evolving slowly with the clock (one cycle
    // per clock wrap, so no jump). Out: R = how much it darkens the ground (Beer's law), before daylight.
    static final String CLOUD_FRAGMENT = """
        #version 330
        uniform vec4 uInfo;
        uniform vec4 uOrigin;
        uniform vec4 uCloud;
        // Rows redone this frame: y % uPhase.y == uPhase.x
        uniform vec2 uPhase;
        out vec4 o;

        float hash(vec2 p)
        {
            vec3 q = fract(vec3(mod(p, 4096.0).xyx) * vec3(0.1031, 0.1030, 0.0973));
            q += dot(q, q.yzx + 33.33);
            return fract((q.x + q.y) * q.z);
        }

        float perlin(vec2 p)
        {
            vec2 i = floor(p);
            vec2 f = fract(p);
            vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
            vec2 g00 = vec2(hash(i), hash(i + 19.19)) * 2.0 - 1.0;
            vec2 g10 = vec2(hash(i + vec2(1.0, 0.0)), hash(i + vec2(20.19, 19.19))) * 2.0 - 1.0;
            vec2 g01 = vec2(hash(i + vec2(0.0, 1.0)), hash(i + vec2(19.19, 20.19))) * 2.0 - 1.0;
            vec2 g11 = vec2(hash(i + vec2(1.0, 1.0)), hash(i + vec2(20.19, 20.19))) * 2.0 - 1.0;
            float a = dot(g00, f);
            float b = dot(g10, f - vec2(1.0, 0.0));
            float c = dot(g01, f - vec2(0.0, 1.0));
            float d = dot(g11, f - vec2(1.0, 1.0));
            return mix(mix(a, b, u.x), mix(c, d, u.x), u.y) * 1.2 + 0.5;
        }

        float worley(vec2 p)
        {
            vec2 i = floor(p);
            vec2 f = fract(p);
            float m = 1.0;
            for (int y = -1; y <= 1; y++) {
                for (int x = -1; x <= 1; x++) {
                    vec2 g = vec2(float(x), float(y));
                    vec2 r = g + vec2(hash(i + g), hash(i + g + 7.7)) - f;
                    m = min(m, dot(r, r));
                }
            }
            return 1.0 - sqrt(m);
        }

        float density(vec2 p, float cover)
        {
            float t = uInfo.w * 0.0015339808;
            vec2 evo = vec2(sin(t * 3.0), cos(t * 2.0)) * 0.7;
            vec2 warp = vec2(perlin(p * 0.55 + evo), perlin(p * 0.55 + vec2(5.2, 1.3) - evo)) - 0.5;
            p += warp * 0.65;
            mat2 rot = mat2(0.8, 0.6, -0.6, 0.8);
            vec2 p2 = rot * p * 2.03 + 3.1;
            float fbm = 0.6 * perlin(p) + 0.4 * perlin(p2);
            float wor = 0.6 * worley(p * 2.2) + 0.4 * worley(rot * p2 * 2.0 + 9.1);
            fbm += 0.12 * (perlin(rot * p2 * 2.1 - 1.7) - 0.5);
            float detail = worley(p * 6.5 + evo) * 0.6 + worley(rot * p * 13.0 - evo) * 0.4;
            float lo = 0.7 - 0.42 * cover;
            float shape = fbm + (wor - 0.5) * 0.55 + (detail - 0.5) * 0.22;
            float dens = smoothstep(lo, lo + 0.3, shape);
            dens = clamp(dens - (1.0 - detail) * 0.5 * (1.0 - dens), 0.0, 1.0);
            dens *= 0.7 + 0.3 * smoothstep(0.2, 0.8, wor);
            return dens;
        }

        void main()
        {
            vec2 fc = floor(gl_FragCoord.xy);
            if (mod(fc.y, uPhase.y) != uPhase.x) discard;
            vec2 m = floor(fc / uInfo.y);
            vec2 sub = (fc - m * uInfo.y + 0.5) / uInfo.y;
            vec2 w = uOrigin.xy + mod(m - uOrigin.xy, uInfo.x) + sub;
            float d = density(w * 0.022 + uCloud.xy, uInfo.z);
            o = vec4((1.0 - exp(-d * 3.5)) * 0.6, d, 0.0, 1.0);
        }
        """;

    // The ground fog's density noise at 4 heights (0 to FOG_LAYER_TOP levels) for each world point: uInfo = ring, texels
    // a square, top height; uDrift = how far the wind has carried it, in noise units. Same noise the march had.
    static final String FOG_FRAGMENT = """
        #version 330
        uniform vec4 uInfo;
        uniform vec4 uOrigin;
        uniform vec4 uDrift;
        uniform vec2 uPhase;
        out vec4 o;

        float hash(vec2 p)
        {
            vec3 q = fract(vec3(mod(p, 4096.0).xyx) * 0.1031);
            q += dot(q, q.yzx + 33.33);
            return fract((q.x + q.y) * q.z);
        }

        float noise(vec2 p)
        {
            vec2 i = floor(p);
            vec2 f = fract(p);
            f = f * f * (3.0 - 2.0 * f);
            return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
        }

        void main()
        {
            vec2 fc = floor(gl_FragCoord.xy);
            if (mod(fc.y, uPhase.y) != uPhase.x) discard;
            vec2 m = floor(fc / uInfo.y);
            vec2 sub = (fc - m * uInfo.y + 0.5) / uInfo.y;
            vec2 wq = uOrigin.xy + mod(m - uOrigin.xy, uInfo.x) + sub;
            vec2 w = wq * 0.22 + uDrift.xy;
            float fine = noise(w * 2.3 - uDrift.xy * 0.7) * 0.35;
            vec4 h = vec4(0.0, 1.0 / 3.0, 2.0 / 3.0, 1.0) * uInfo.z * 1.3;
            o = vec4(noise(w + h.x), noise(w + h.y), noise(w + h.z), noise(w + h.w)) * 0.65 + fine;
        }
        """;

    // Better Ground Snow's field for a world point: R = the lie of the snow 0..1, G and B = how it changes 0.12 squares
    // east and south (0.5 + 2.5 x), A = the low noise that bends the wind ripples. Same noise as the chunk shader had.
    static final String SNOW_FRAGMENT = """
        #version 330
        uniform vec4 uInfo;
        uniform vec4 uOrigin;
        out vec4 o;

        float hash(vec2 p)
        {
            vec3 q = fract(vec3(mod(p, 4096.0).xyx) * 0.1031);
            q += dot(q, q.yzx + 33.33);
            return fract((q.x + q.y) * q.z);
        }

        float noise(vec2 p)
        {
            vec2 i = floor(p);
            vec2 f = fract(p);
            f = f * f * (3.0 - 2.0 * f);
            return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
        }

        float field(vec2 w)
        {
            float n = noise(w * 0.35) * 0.55 + noise(mat2(0.8, 0.6, -0.6, 0.8) * w * 1.1 + 7.3) * 0.3;
            return n + noise(mat2(0.6, -0.8, 0.8, 0.6) * w * 3.7 - 2.1) * 0.15;
        }

        void main()
        {
            vec2 fc = floor(gl_FragCoord.xy);
            vec2 m = floor(fc / uInfo.y);
            vec2 sub = (fc - m * uInfo.y + 0.5) / uInfo.y;
            vec2 w = uOrigin.xy + mod(m - uOrigin.xy, uInfo.x) + sub;
            float n = field(w);
            float hx = field(w + vec2(0.12, 0.0)) - n;
            float hy = field(w + vec2(0.0, 0.12)) - n;
            o = vec4(n, clamp(0.5 + hx * 2.5, 0.0, 1.0), clamp(0.5 + hy * 2.5, 0.0, 1.0), noise(w * 1.3));
        }
        """;

    static String stateText() {
        return "skySun=" + (sunWanted ? 1 : 0) + ";skyCloud=" + (cloudWanted ? 1 : 0) + ";skyFog=" + (fogWanted ? 1 : 0) + ";skySunTexels=" + sunTexels
            + ";skyCloudTexels=" + cloudTexels + ";skyFogTexels=" + fogTexels + ";skySnow=" + (snowWanted ? 1 : 0) + ";skySnowTexels=" + snowTexels + ";skyFailed=" + (failed ? 1 : 0);
    }
}
