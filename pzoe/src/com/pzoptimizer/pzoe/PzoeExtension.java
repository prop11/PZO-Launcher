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
                    + GpuTimer.stateText() + ";" + ModelSnow.stateText() + ";" + LightBeams.stateText() + ";" + RainRunoff.stateText() + ";" + FogField.stateText();
            // Roof Runoff has no shader to ask for it: its card sends on/off and the amount (percent)
            case "runoff":
                RainRunoff.configure(Boolean.TRUE.equals(a), b instanceof Number n ? n.doubleValue() : 100.0);
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
    }

    @Override
    public void reset() {
        BakePass.reset();
    }
}
