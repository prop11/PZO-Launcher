package com.pzoptimizer.pzoe;

import com.pzoptimizer.AsmSupport;
import com.pzoptimizer.Extension;
import com.pzoptimizer.FoliageLod;
import com.pzoptimizer.PZOLogger;
import java.util.HashSet;
import java.util.Set;

/**
 * PZO Enhancements' engine, loaded by PZO from PZOE.jar in the mod's folder. Everything here only exists in a game
 * where the mod is installed and on; each effect then still waits for its shader patch to be live before it does any
 * work, so a card that's off costs nothing either.
 */
public final class PzoeExtension implements Extension {
    private static final String PUDDLES = "zombie/iso/IsoPuddles", PUDDLES_SHADER = "zombie/iso/PuddlesShader",
        WEATHER_SHADER = "zombie/iso/weather/WeatherShader", RENDER_CELL = "zombie/iso/fboRenderChunk/FBORenderCell";

    @Override
    public int apiVersion() {
        return API;
    }

    @Override
    public String name() {
        return "PZO Enhancements engine";
    }

    @Override
    public void init() {
        FoliageLod.bakeWindFoliage = PixelLight::bakeWindFoliage;
    }

    @Override
    public Set<String> earlyTargets() {
        Set<String> s = new HashSet<>();
        for (String t : PixelLight.TARGETS) s.add(t.replace('/', '.'));
        for (String t : ModelSnow.TARGETS) s.add(t.replace('/', '.'));
        s.add(PUDDLES.replace('/', '.'));
        s.add(PUDDLES_SHADER.replace('/', '.'));
        s.add(WEATHER_SHADER.replace('/', '.'));
        s.add(GroundSnow.SNOW_RENDER.replace('/', '.'));
        s.add(Precipitation.WEATHER_FX.replace('/', '.'));
        s.add(NaturalGround.SPRITE.replace('/', '.'));
        return s;
    }

    @Override
    public byte[] transform(ClassLoader loader, String cn, byte[] bytes) {
        byte[] out = PixelLight.postTransform(loader, cn, bytes);
        byte[] cur = out != null ? out : bytes;
        byte[] snow = ModelSnow.postTransform(loader, cn, cur);
        if (snow != null) out = cur = snow;
        byte[] own = switch (cn) {
            case PUDDLES -> logged(AsmSupport.transform(cur, loader, "IsoPuddles splash frame", PuddleSplash::patchPuddles),
                "IsoPuddles: puddle splash frame hook");
            case PUDDLES_SHADER -> logged(AsmSupport.transform(cur, loader, "PuddlesShader splash uniforms", PuddleSplash::patchShader),
                "PuddlesShader.updatePuddlesParams: PZOTime/PZOSplash for patched shaders");
            case WEATHER_SHADER -> logged(AsmSupport.transform(cur, loader, "WeatherShader screen pass uniforms", ScreenPass::patchShader),
                "WeatherShader: haze and grade uniforms for patched screen shaders");
            case RENDER_CELL -> logged(AsmSupport.transform(cur, loader, "FBORenderCell roof runoff", RainRunoff::patchCell),
                "FBORenderCell.renderRainSplashes: roof runoff drops");
            case GroundSnow.SNOW_RENDER -> logged(AsmSupport.transform(cur, loader, "FBORenderSnow tiles", GroundSnow::patchRenderSnow),
                "FBORenderSnow.RenderSnow: vanilla snow tiles off while Better Ground Snow draws");
            case Precipitation.WEATHER_FX -> logged(AsmSupport.transform(cur, loader, "IsoWeatherFX precipitation", Precipitation::patchWeatherFx),
                "IsoWeatherFX: rain and snow particles replaced by the GPU pass while Better Rain and Snow is on");
            case NaturalGround.SPRITE -> logged(AsmSupport.transform(cur, loader, "IsoSprite natural ground", NaturalGround::patchSprite),
                "IsoSprite.startTileDepthShader: Natural Ground uniforms on floor pieces");
            default -> null;
        };
        return own != null ? own : out;
    }

    private static byte[] logged(byte[] b, String what) {
        if (b != null) PZOLogger.success("[PZOE] ASM-patched " + what);
        return b;
    }

    @Override
    public Object modApi(String op, Object a, Object b, Object c) {
        switch (op) {
            // Bake passes (PZOApi.addBakePass): staged like shader patches, live on shaderCommit
            case "bakePass":
                return Boolean.valueOf(a != null && b != null && BakePass.add(a.toString(), b.toString()));
            case "bakePassOn":
                return Boolean.valueOf(a != null && BakePass.setOn(a.toString(), Boolean.TRUE.equals(b)));
            case "bakeStatus":
                return BakePass.statusOf(a == null ? null : a.toString());
            // Live state of the effects, shown on their settings cards in -debug
            case "chunkEffects":
                return PixelLight.stateText() + ";" + PuddleSplash.stateText() + ";" + ScreenPass.stateText() + ";"
                    + GpuTimer.stateText() + ";" + ModelSnow.stateText() + ";" + LightBeams.stateText() + ";" + RainRunoff.stateText() + ";" + FogField.stateText() + ";" + GroundSnow.stateText() + ";" + Precipitation.stateText() + ";" + NaturalGround.stateText() + ";" + SkyCache.stateText();
            // Per-Pixel Lighting card: the lighting code is always in the chunk shader, this says whether to use it
            case "pixelLight":
                PixelLight.lightWanted = Boolean.TRUE.equals(a);
                return Boolean.TRUE;
            // One of the chunk shader's card strengths (index into PZOTune0..2, value)
            case "tune":
                if (a instanceof Number i && b instanceof Number v && i.intValue() >= 0 && i.intValue() < PixelLight.tune.length) {
                    PixelLight.tune[i.intValue()] = v.floatValue();
                }
                return Boolean.TRUE;
            // Texels a square for the world-space sun shadow cache
            case "skyDetail":
                SkyCache.detail = a instanceof Number n ? Math.max(2, Math.min(8, n.intValue())) : 8;
                return Boolean.TRUE;
            // Roof Runoff has no shader to ask for it: its card sends on/off and the amount (percent)
            case "runoff":
                RainRunoff.configure(Boolean.TRUE.equals(a), b instanceof Number n ? n.doubleValue() : 100.0);
                return Boolean.TRUE;
            // Better Ground Snow: build-up speed in percent (the shader itself says the card is on)
            case "groundSnow":
                GroundSnow.configure(a instanceof Number n ? n.doubleValue() : 100.0);
                return Boolean.TRUE;
            // Better Rain and Snow: on, quality 1-3, amount percent
            case "precip":
                Precipitation.configure(Boolean.TRUE.equals(a), b instanceof Number n ? n.intValue() : 2, c instanceof Number m ? m.doubleValue() : 100.0);
                return Boolean.TRUE;
            // Mods that make those effects stand down: "chunk=A,B;splash=C"
            case "chunkConflicts":
                return PixelLight.conflictText();
            default:
                return UNHANDLED;
        }
    }

    @Override
    public void commit() {
        BakePass.commit();
        NaturalGround.commit();
    }

    @Override
    public void reset() {
        BakePass.reset();
    }
}
