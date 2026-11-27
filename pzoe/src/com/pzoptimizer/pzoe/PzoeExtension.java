package com.pzoptimizer.pzoe;

import com.pzoptimizer.AsmSupport;
import com.pzoptimizer.Extension;
import com.pzoptimizer.FoliageLod;
import com.pzoptimizer.PZOLogger;
import com.pzoptimizer.pzoe.gore.GoreCore;
import com.pzoptimizer.pzoe.weather.WeatherCore;
import com.pzoptimizer.pzoe.weather.WeatherStorm;
import com.pzoptimizer.pzoe.water.WaterWaves;
import com.pzoptimizer.pzoe.water.WaterAnimation;
import com.pzoptimizer.pzoe.water.WaterWading;
import com.pzoptimizer.pzoe.water.WaterVehicles;
import com.pzoptimizer.pzoe.water.WaterSwimming;
import com.pzoptimizer.pzoe.water.WaterZombies;
import com.pzoptimizer.pzoe.water.WaterBuoyancy;
import com.pzoptimizer.pzoe.water.UnderwaterView;
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
        GoreFx.install();
    }

    @Override
    public Set<String> earlyTargets() {
        Set<String> s = new HashSet<>();
        for (String t : PixelLight.TARGETS) s.add(t.replace('/', '.'));
        for (String t : ModelSnow.TARGETS) s.add(t.replace('/', '.'));
        for (String t : MuzzleFlashHooks.TARGETS) s.add(t.replace('/', '.'));
        s.add(PUDDLES.replace('/', '.'));
        s.add(PUDDLES_SHADER.replace('/', '.'));
        s.add(WaterWaves.TARGET.replace('/', '.'));
        s.add(GpuTimer.WATER_RENDERER.replace('/', '.'));
        s.add(WaterWading.TARGET.replace('/', '.'));
        s.add(WaterAnimation.TARGET.replace('/', '.'));
        s.add(WaterSwimming.MOVING_OBJECT.replace('/', '.'));
        s.add(com.pzoptimizer.pzoe.weather.WeatherEffects.ZOMBIE.replace('/', '.'));
        s.add(GoreCore.SQUARE.replace('/', '.'));
        s.add(SmokeExposure.CHARACTER.replace('/', '.'));
        s.add(FireEvents.FIRE.replace('/', '.'));
        s.add(FireEvents.MANAGER.replace('/', '.'));
        s.add(GoreCore.COMBAT.replace('/', '.'));
        s.add(GoreKillCam.TARGET.replace('/', '.'));
        s.add(GoreCore.DEAD_BODY.replace('/', '.'));
        s.add(WaterBuoyancy.CELL.replace('/', '.'));
        s.add(WaterBuoyancy.ITEM.replace('/', '.'));
        s.add(UnderwaterView.SOUND_MANAGER.replace('/', '.'));
        s.add(WeatherAudio.AMBIENT.replace('/', '.'));
        s.add(RoomAcoustics.EMITTER.replace('/', '.'));
        s.add(RoomAcoustics.OCCLUSION.replace('/', '.'));
        s.add(GeneratorLights.TARGET.replace('/', '.'));
        s.add(WeatherGround.MASK.replace('/', '.'));
        for (String target : WeatherAudio.PARAMETERS) s.add(target.replace('/', '.'));
        s.add(com.pzoptimizer.pzoe.weather.WeatherEffects.VEHICLE.replace('/', '.'));
        s.add(com.pzoptimizer.pzoe.weather.WeatherSnow.CAR.replace('/', '.'));
        s.add(WEATHER_SHADER.replace('/', '.'));
        s.add(GroundSnow.SNOW_RENDER.replace('/', '.'));
        s.add(Precipitation.WEATHER_FX.replace('/', '.'));
        s.add(NaturalGround.SPRITE.replace('/', '.'));
        s.add(WeatherCore.CLIMATE_CLASS.replace('/', '.'));
        s.add(com.pzoptimizer.pzoe.weather.WeatherAuthority.FLOAT_CLASS.replace('/', '.'));
        s.add(com.pzoptimizer.pzoe.weather.WeatherAuthority.BOOL_CLASS.replace('/', '.'));
        s.add(WeatherStorm.THUNDER_CLASS.replace('/', '.'));
        return s;
    }

    @Override
    public byte[] transform(ClassLoader loader, String cn, byte[] bytes) {
        byte[] out = PixelLight.postTransform(loader, cn, bytes);
        byte[] cur = out != null ? out : bytes;
        byte[] snow = ModelSnow.postTransform(loader, cn, cur);
        if (snow != null) out = cur = snow;
        if (MuzzleFlashHooks.TARGETS.contains(cn)) {
            byte[] flash = logged(AsmSupport.transform(cur, loader, "Muzzle flash " + cn, MuzzleFlashHooks::patch),
                cn + ": PZOE muzzle flash hook");
            if (flash != null) out = cur = flash;
        }
        byte[] own = switch (cn) {
            case PUDDLES -> {
                byte[] splash = logged(AsmSupport.transform(cur, loader, "IsoPuddles splash frame", PuddleSplash::patchPuddles),
                    "IsoPuddles: puddle splash frame hook");
                byte[] dry = logged(AsmSupport.transform(splash != null ? splash : cur, loader, "IsoPuddles weather drying", WeatherCore::patchPuddlesUpdate),
                    "IsoPuddles.update: PZOE weather ground drying by sun and wind");
                yield dry != null ? dry : splash;
            }
            case PUDDLES_SHADER -> logged(AsmSupport.transform(cur, loader, "PuddlesShader splash uniforms", PuddleSplash::patchShader),
                "PuddlesShader.updatePuddlesParams: PZOTime/PZOSplash for patched shaders");
            case WaterWaves.TARGET -> logged(AsmSupport.transform(cur, loader, "WaterShader wave atlas", WaterWaves::patch),
                "WaterShader.updateWaterParams: PZOE wave atlas for patched water");
            case GpuTimer.WATER_RENDERER -> logged(AsmSupport.transform(cur, loader, "ModelManager water GPU timing", GpuTimer::patchWater),
                "ModelManager.RenderWater: debug water GPU timing");
            case WaterWading.TARGET -> logged(AsmSupport.transform(cur, loader, "IsoPlayer wading", WaterWading::patch),
                "IsoPlayer: sandbox-controlled wading and swimming");
            case WaterAnimation.TARGET -> logged(AsmSupport.transform(cur, loader, "AnimationPlayer water pose", WaterAnimation::patch),
                "AnimationPlayer: sandbox-controlled wading, swim strokes and treading water");
            case WaterSwimming.MOVING_OBJECT -> logged(AsmSupport.transform(cur, loader, "IsoMovingObject swimming collision", WaterSwimming::patchMovingObject),
                "IsoMovingObject.postupdate: swimming through open water");
            case WaterBuoyancy.CELL -> logged(AsmSupport.transform(cur, loader, "IsoCell buoyancy", WaterBuoyancy::patchCell),
                "IsoCell.update: immersed world items");
            case WaterBuoyancy.ITEM -> logged(AsmSupport.transform(cur, loader, "IsoWorldInventoryObject buoyancy", WaterBuoyancy::patchItem),
                "IsoWorldInventoryObject: register immersed items");
            case RoomAcoustics.EMITTER -> logged(AsmSupport.transform(cur, loader, "Room audio reflections", RoomAcoustics::patchEmitter),
                "FMODSoundEmitter: room-dependent early reflections");
            case RoomAcoustics.OCCLUSION -> logged(AsmSupport.transform(cur, loader, "Room audio openings", RoomAcoustics::patchOcclusion),
                "ParameterOcclusion: sound transmission through room openings");
            case WeatherGround.MASK -> logged(AsmSupport.transform(cur, loader, "Weather mask roof holes", WeatherGround::patchMask),
                "WeatherFxMask.isInPlayerBuilding: rain through roof holes");
            case GeneratorLights.TARGET -> logged(AsmSupport.transform(cur, loader, "Generator lighting", GeneratorLights::patch),
                "IsoGenerator: visual startup and low-fuel lighting");
            case WeatherAudio.AMBIENT -> logged(AsmSupport.transform(cur, loader, "Sheltered weather audio", WeatherAudio::patchAmbient),
                "AmbientStreamManager.update: listener shelter and weather loops");
            case WeatherAudio.RAIN, WeatherAudio.SNOW, WeatherAudio.WIND -> logged(
                AsmSupport.transform(cur, loader, "Weather audio parameter", WeatherAudio::patchParameter),
                cn + ": sheltered weather intensity");
            case UnderwaterView.SOUND_MANAGER -> logged(AsmSupport.transform(cur, loader, "SoundManager underwater filter", UnderwaterView::patchSoundManager),
                "SoundManager.Update: PZOE underwater low pass");
            case GoreCore.DEAD_BODY -> logged(AsmSupport.transform(cur, loader, "IsoDeadBody buoyancy", WaterBuoyancy::patchCorpse),
                "IsoDeadBody.render: PZOE water bobbing");
            case WEATHER_SHADER -> logged(AsmSupport.transform(cur, loader, "WeatherShader screen pass uniforms", ScreenPass::patchShader),
                "WeatherShader: haze and grade uniforms for patched screen shaders");
            case RENDER_CELL -> {
                byte[] runoff = logged(AsmSupport.transform(cur, loader, "FBORenderCell roof runoff", RainRunoff::patchCell),
                    "FBORenderCell.renderRainSplashes: roof runoff drops");
                byte[] blood = logged(AsmSupport.transform(runoff != null ? runoff : cur, loader, "FBORenderCell floor blood", GoreCore::patchCell),
                    "FBORenderCell.renderOneLevel_Blood: floor splat sprites off while Ground Blood draws them");
                byte[] base = blood != null ? blood : runoff;
                byte[] glass = logged(AsmSupport.transform(base != null ? base : cur, loader, "Window condensation", WindowCondensation::patch),
                    "FBORenderCell: condensation on visible window glass");
                yield glass != null ? glass : base;
            }
            case GoreCore.CHUNK -> logged(AsmSupport.transform(cur, loader, "IsoChunk blood splat", GoreCore::patchChunk),
                "IsoChunk.addBloodSplat: no chunk re-bake per splat while Ground Blood draws them");
            case FireEvents.FIRE -> logged(AsmSupport.transform(cur, loader, "IsoFire aftermath", FireEvents::patchFire),
                "IsoFire: distinguish spread and record heat damage");
            case FireEvents.MANAGER -> logged(AsmSupport.transform(cur, loader, "IsoFireManager ignition", FireEvents::patchManager),
                "IsoFireManager: record ignition for accepted fires");
            case GoreCore.SQUARE -> {
                byte[] blood = logged(AsmSupport.transform(cur, loader, "IsoGridSquare blood removal", GoreCore::patchSquare),
                    "IsoGridSquare.removeBlood: Ground Blood wipes cleaned blood");
                byte[] water = logged(AsmSupport.transform(blood != null ? blood : cur, loader, "IsoGridSquare swimming collision", WaterSwimming::patchSquare),
                    "IsoGridSquare.testCollideAdjacent: open water transitions for swimming");
                byte[] upstream = water != null ? water : blood;
                byte[] splash = logged(AsmSupport.transform(upstream != null ? upstream : cur, loader, "IsoGridSquare water splash", PuddleSplash::patchWaterSplash),
                    "IsoGridSquare.startWaterSplash: PZOE water ripples");
                byte[] clean = logged(AsmSupport.transform(splash != null ? splash : upstream != null ? upstream : cur,
                    loader, "IsoGridSquare soot cleaning", FireEvents::patchCleaning), "IsoGridSquare: clean soot through vanilla stains");
                yield clean != null ? clean : splash != null ? splash : upstream;
            }
            case GroundSnow.SNOW_RENDER -> logged(AsmSupport.transform(cur, loader, "FBORenderSnow tiles", GroundSnow::patchRenderSnow),
                "FBORenderSnow.RenderSnow/gridSquareIsSnow: vanilla snow tiles off and snow checks follow Better Ground Snow while it draws");
            case Precipitation.WEATHER_FX -> logged(AsmSupport.transform(cur, loader, "IsoWeatherFX precipitation", Precipitation::patchWeatherFx),
                "IsoWeatherFX: rain and snow particles replaced by the GPU pass while Better Rain and Snow is on");
            case PuddleSplash.FOOTSTEP2 -> logged(AsmSupport.transform(cur, loader, "ParameterFootstepMaterial2 puddles", PuddleSplash::patchFootstep2),
                "ParameterFootstepMaterial2: footsteps splash in any drawn puddle, not only full-depth ones");
            case NaturalGround.SPRITE -> logged(AsmSupport.transform(cur, loader, "IsoSprite natural ground", NaturalGround::patchSprite),
                "IsoSprite.startTileDepthShader: Natural Ground uniforms on floor pieces");
            case com.pzoptimizer.pzoe.weather.WeatherEffects.ZOMBIE -> {
                byte[] weather = logged(AsmSupport.transform(cur, loader, "IsoZombie weather senses", com.pzoptimizer.pzoe.weather.WeatherEffects::patchZombie),
                    "IsoZombie.updateVisionRadius: PZOE weather snow and hail cut zombie sight");
                byte[] water = logged(AsmSupport.transform(weather != null ? weather : cur, loader, "IsoZombie water exposure", WaterZombies::patch),
                    "IsoZombie.update: PZOE open water exposure");
                yield water != null ? water : weather;
            }
            case com.pzoptimizer.pzoe.weather.WeatherEffects.CHARACTER -> {
                byte[] hearing = logged(AsmSupport.transform(cur, loader, "IsoGameCharacter weather hearing",
                    com.pzoptimizer.pzoe.weather.WeatherEffects::patchCharacter),
                    "IsoGameCharacter.getWeatherHearingMultiplier: PZOE weather wind and hail cover noise");
                byte[] smoke = logged(AsmSupport.transform(hearing != null ? hearing : cur, loader,
                    "IsoGameCharacter smoke exposure", SmokeExposure::patchBlur), "IsoGameCharacter.getBlurFactor: smoke exposure");
                yield smoke != null ? smoke : hearing;
            }
            case com.pzoptimizer.pzoe.weather.WeatherEffects.VEHICLE -> {
                byte[] weather = logged(AsmSupport.transform(cur, loader, "BaseVehicle weather grip", com.pzoptimizer.pzoe.weather.WeatherEffects::patchVehicle),
                    "BaseVehicle.updateBulletStats: PZOE weather grip on snow, ice and wet roads");
                byte[] water = logged(AsmSupport.transform(weather != null ? weather : cur, loader, "BaseVehicle water damage", WaterVehicles::patchVehicle),
                    "BaseVehicle.update: PZOE water engine stall and damage");
                yield water != null ? water : weather;
            }
            case com.pzoptimizer.pzoe.weather.WeatherSnow.CAR -> {
                byte[] weather = logged(AsmSupport.transform(cur, loader, "CarController deep snow", com.pzoptimizer.pzoe.weather.WeatherSnow::patchCar),
                    "CarController.checkTire: PZOE weather vehicles bog down in deep snow");
                byte[] water = logged(AsmSupport.transform(weather != null ? weather : cur, loader, "CarController water drag", WaterVehicles::patchController),
                    "CarController.update: PZOE water drag");
                yield water != null ? water : weather;
            }
            case WeatherStorm.THUNDER_CLASS -> {
                byte[] storm = logged(AsmSupport.transform(cur, loader, "ThunderStorm weather", WeatherStorm::patchThunder),
                    "ThunderStorm: PZOE weather lightning direction and thunder gameplay");
                byte[] audio = logged(AsmSupport.transform(storm != null ? storm : cur, loader, "ThunderStorm shelter", WeatherAudio::patchThunder),
                    "ThunderStorm: sheltered strikes and rumbles");
                yield audio != null ? audio : storm;
            }
            case com.pzoptimizer.pzoe.weather.WeatherAuthority.FLOAT_CLASS -> logged(AsmSupport.transform(cur, loader, "ClimateFloat writers", com.pzoptimizer.pzoe.weather.WeatherAuthority::patchFloat),
                "ClimateFloat setters: PZOE weather follows other mods' climate values");
            case com.pzoptimizer.pzoe.weather.WeatherAuthority.BOOL_CLASS -> logged(AsmSupport.transform(cur, loader, "ClimateBool writers", com.pzoptimizer.pzoe.weather.WeatherAuthority::patchBool),
                "ClimateBool setters: PZOE weather follows other mods' snow");
            case AutumnTrees.TREE -> logged(AsmSupport.transform(cur, loader, "IsoTree autumn colours", AutumnTrees::patch),
                "IsoTree.renderInner: Autumn Colours tint each tree its own way");
            case WeatherCore.CLIMATE_CLASS -> logged(AsmSupport.transform(cur, loader, "ClimateManager weather", WeatherCore::patchClimate),
                "ClimateManager: PZOE weather tick and weather look");
            default -> null;
        };
        byte[] base = own != null ? own : out;
        byte[] gore = goreTransform(loader, cn, base != null ? base : bytes);
        return gore != null ? gore : base;
    }

    // PZOE Gore's patches on classes other effects may already have patched above
    private static byte[] goreTransform(ClassLoader loader, String cn, byte[] cur) {
        return switch (cn) {
            case GoreCore.CHARACTER -> logged(AsmSupport.transform(cur, loader, "IsoGameCharacter gore", GoreCore::patchCharacter),
                "IsoGameCharacter.addBlood/doDeathSplatterAndSounds: PZOE gore wounds and kills");
            case GoreCore.ZOMBIE -> logged(AsmSupport.transform(cur, loader, "IsoZombie gore", GoreCore::patchZombie),
                "IsoZombie: PZOE gore vehicle hits, pool reuse, no bite without a jaw");
            case GoreCore.DEAD_BODY -> logged(AsmSupport.transform(cur, loader, "IsoDeadBody gore", GoreCore::patchDeadBody),
                "IsoDeadBody: PZOE gore record moves to the corpse");
            case GoreCore.BODY_DAMAGE -> logged(AsmSupport.transform(cur, loader, "BodyDamage gore", GoreCore::patchBodyDamage),
                "BodyDamage.AddRandomDamageFromZombie: PZOE gore armless zombies");
            case GoreCore.EAT_STATE -> logged(AsmSupport.transform(cur, loader, "ZombieEatBodyState gore", GoreCore::patchEat),
                "ZombieEatBodyState.execute: PZOE gore feeding wounds");
            case GoreKillCam.TARGET -> logged(AsmSupport.transform(cur, loader, "PlayerCamera gore", GoreKillCam::patchCamera),
                "PlayerCamera.center: PZOE X-ray kill cam");
            case GoreCore.COMBAT -> {
                byte[] gore = logged(AsmSupport.transform(cur, loader, "CombatManager gore", GoreCore::patchCombat),
                    "CombatManager: resolved wound locations and close-range pooled giblets");
                byte[] impacts = logged(AsmSupport.transform(gore != null ? gore : cur, loader, "CombatManager bullet impacts", BulletImpacts::patch),
                    "CombatManager.hitIsoGridSquare: PZOE bullet impacts");
                yield impacts != null ? impacts : gore;
            }
            case GoreCore.GIBLETS -> logged(AsmSupport.transform(cur, loader, "IsoZombieGiblets gore", GoreCore::patchGiblets),
                "IsoZombieGiblets: PZOE gore draws its own gibs");
            case GoreCore.GAME_TIME -> logged(AsmSupport.transform(cur, loader, "GameTime gore", GoreCore::patchGameTime),
                "GameTime.getSlomoMultiplier: PZOE gore hit-stop and kill cam (single player)");
            case com.pzoptimizer.pzoe.gore.GoreAtlas.ATLAS -> logged(AsmSupport.transform(cur, loader, "DeadBodyAtlas key gore",
                com.pzoptimizer.pzoe.gore.GoreAtlas::patchAtlas), "DeadBodyAtlas.getBodyKey: PZOE gore corpses get their own picture");
            case com.pzoptimizer.pzoe.gore.GoreAtlas.JOB -> {
                byte[] job = logged(AsmSupport.transform(cur, loader, "DeadBodyAtlas job gore",
                    com.pzoptimizer.pzoe.gore.GoreAtlas::patchJob), "DeadBodyAtlas.RenderJob.renderMain: PZOE gore record while a corpse draws");
                byte[] bounds = logged(AsmSupport.transform(job != null ? job : cur, loader, "DeadBodyAtlas detached bounds",
                    com.pzoptimizer.pzoe.gore.GoreAtlas::patchDetachedBounds), "DeadBodyAtlas.RenderJob: PZOE detached part bounds");
                if (bounds != null && job != null) GoreDetached.enableBounds();
                yield bounds != null ? bounds : job;
            }
            default -> null;
        };
    }

    private static float pct(Object a, double max, float fallback) {
        return a instanceof Number n ? (float) Math.max(0.0, Math.min(max, n.doubleValue() / 100.0)) : fallback;
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
                    + GpuTimer.stateText() + ";" + ModelSnow.stateText() + ";" + LightBeams.stateText() + ";" + RainRunoff.stateText() + ";" + FogField.stateText() + ";" + GroundSnow.stateText() + ";" + Precipitation.stateText() + ";" + NaturalGround.stateText() + ";" + SkyCache.stateText() + ";" + FlickerLights.stateText() + ";" + LightColours.stateText() + ";" + BounceLight.stateText() + ";" + GoreMap.stateText() + ";" + GoreFx.stateText() + ";" + GoreModel.stateText();
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
            // Bounce Light: strength and reach in percent (the shader itself says the card is on)
            case "gi":
                // 100 percent is BASE of the raw bounce: the raw sum at night was far too bright even at 50
                BounceLight.strength = (a instanceof Number n ? (float) Math.max(0.0, Math.min(2.0, n.doubleValue() / 100.0)) : 1f) * BounceLight.BASE;
                BounceLight.reach = (b instanceof Number n2 ? (float) Math.max(0.25, Math.min(2.0, n2.doubleValue() / 100.0)) : 1f) * BounceLight.REACH_BASE;
                return Boolean.TRUE;
            // Temporal Smoothing has no shader patch: on/off and blend in percent
            case "temporal":
                TemporalPass.on = Boolean.TRUE.equals(a);
                if (!TemporalPass.on) TemporalPass.reset();
                TemporalPass.blend = b instanceof Number n3 ? (float) Math.max(0.0, Math.min(0.9, n3.doubleValue() / 100.0)) : 0.5f;
                return Boolean.TRUE;
            // Window Reflections: strength in percent (the shader itself says the card is on)
            case "weatherAudio":
                WeatherAudio.enabled = Boolean.TRUE.equals(a);
                return Boolean.TRUE;
            case "windShelter":
                PixelLight.windShelter = Boolean.TRUE.equals(a);
                return Boolean.TRUE;
            case "roomAcoustics":
                RoomAcoustics.enabled = Boolean.TRUE.equals(a);
                return Boolean.TRUE;
            case "windDebris":
                WindDebris.enabled = Boolean.TRUE.equals(a);
                WindDebris.ownsLeaves = Boolean.TRUE.equals(b);
                return Boolean.TRUE;
            case "weatherGround":
                WeatherGround.leaks = Boolean.TRUE.equals(a);
                WeatherGround.drainage = Boolean.TRUE.equals(b);
                WeatherGround.debris = Boolean.TRUE.equals(c);
                return Boolean.TRUE;
            case "soilMoisture":
                NaturalGround.soilWanted = Boolean.TRUE.equals(a);
                return Boolean.TRUE;
            case "generatorFumes":
                SmokePlumes.fumes = Boolean.TRUE.equals(a);
                return Boolean.TRUE;
            case "hordeTrails":
                HordeTrails.amount = a instanceof Number n ? (float) Math.max(0.0, Math.min(2.0, n.doubleValue() / 100.0)) : 1f;
                return Boolean.TRUE;
            case "engineSmoke":
                SmokePlumes.engineSmoke = Boolean.TRUE.equals(a);
                return Boolean.TRUE;
            case "gunSmoke":
                SmokePlumes.gunSmoke = Boolean.TRUE.equals(a);
                return Boolean.TRUE;
            case "blackoutWave":
                FlickerLights.blackoutWave = Boolean.TRUE.equals(a);
                return Boolean.TRUE;
            case "generatorLights":
                GeneratorLights.enabled = Boolean.TRUE.equals(a);
                return Boolean.TRUE;
            case "waterIceChanged":
                return zombie.network.GameServer.server ? com.pzoptimizer.pzoe.water.WaterIce.changedHoles() : "";
            case "waterIceApply":
                if (zombie.network.GameClient.client) com.pzoptimizer.pzoe.water.WaterIce.apply(a == null ? "" : a.toString());
                return Boolean.TRUE;
            case "windowCondensation":
                WindowCondensation.enabled = Boolean.TRUE.equals(a);
                return Boolean.TRUE;
            case "winRefl":
                PixelLight.winReflStrength = a instanceof Number n ? (float) Math.max(0.0, Math.min(2.0, n.doubleValue() / 100.0)) : 1f;
                return Boolean.TRUE;
            // Custom LUT on or off (Colour-Blind Filter and Custom LUT): read Zomboid/PZOE/lut.cube
            case "lut":
                Lut.want(Boolean.TRUE.equals(a));
                return Boolean.TRUE;
            // Lens Glare: strength in percent (the shader itself says the card is on)
            case "glare":
                Glare.amount = a instanceof Number n ? (float) Math.max(0.0, Math.min(2.0, n.doubleValue() / 100.0)) : 1f;
                return Boolean.TRUE;
            // Fireflies, Eye Shine, Character Shadows, Contact Shadows: strength in percent (their shaders say they're on)
            case "fireflies":
                Fireflies.amount = pct(a, 2.0, 1f);
                return Boolean.TRUE;
            case "eyeShine":
                EyeShine.amount = pct(a, 2.0, 1f);
                return Boolean.TRUE;
            // Light Colours: strength in percent (the shader itself says the card is on)
            case "lightColours":
                LightColours.amount = pct(a, 2.0, 1f);
                return Boolean.TRUE;
            // Torch and Headlight Beams: worn torches (battery) and worn vehicle lights (condition) on or off
            case "beamWear":
                LightBeams.batteryWear = !Boolean.FALSE.equals(a);
                LightBeams.conditionWear = !Boolean.FALSE.equals(b);
                return Boolean.TRUE;
            case "charShadows":
                CharShadows.amount = pct(a, 2.0, 1f);
                CharShadows.vehicles = !Boolean.FALSE.equals(b);
                return Boolean.TRUE;
            case "contact":
                PixelLight.contactStrength = pct(a, 2.0, 1f);
                return Boolean.TRUE;
            // Flies and Rot: amount in percent, rot stains on/off
            case "corpseFlies":
                CorpseFlies.amount = pct(a, 2.0, 1f);
                CorpseFlies.stains = !Boolean.FALSE.equals(b);
                return Boolean.TRUE;
            // Autumn Colours has no shader: on/off and strength in percent
            case "autumnTrees":
                AutumnTrees.on = Boolean.TRUE.equals(a);
                AutumnTrees.amount = pct(b, 1.5, 1f);
                return Boolean.TRUE;
            // Footprints and Tyre Tracks' Tracked-in mud on/off
            case "trailsMud":
                PuddleSplash.trackedMud = Boolean.TRUE.equals(a);
                return Boolean.TRUE;
            // Dust and Mud Trails: amount in percent (the shader itself says the card is on)
            case "dust":
                DustTrails.amount = a instanceof Number n ? (float) Math.max(0.0, Math.min(2.0, n.doubleValue() / 100.0)) : 1f;
                return Boolean.TRUE;
            // Morning Frost: strength in percent (the shader itself says the card is on)
            // Moonlight: card on and strength in percent
            case "moonlight":
                PixelLight.moonAmount = Boolean.TRUE.equals(a) && b instanceof Number n ? (float) Math.max(0.0, Math.min(2.0, n.doubleValue() / 100.0)) : 0f;
                return Boolean.TRUE;
            // Leaf Shadows: how far the crowns' shadows sway in percent (the shader itself says the card is on)
            case "leafSway":
                PixelLight.leafSwayAmount = a instanceof Number n ? (float) Math.max(0.0, Math.min(2.0, n.doubleValue() / 100.0)) : 1f;
                return Boolean.TRUE;
            // Fire Glow: strength in percent (the shader itself says the card is on)
            case "fireGlow":
                FireGlow.amount = a instanceof Number n ? (float) Math.max(0.0, Math.min(2.0, n.doubleValue() / 100.0)) : 1f;
                return Boolean.TRUE;
            // Bullet Impacts: size in percent (the shaders themselves say the card is on)
            case "bulletImpacts":
                BulletImpacts.amount = a instanceof Number n ? (float) Math.max(0.0, Math.min(2.0, n.doubleValue() / 100.0)) : 1f;
                return Boolean.TRUE;
            case "frost":
                Frost.amount = a instanceof Number n ? (float) Math.max(0.0, Math.min(2.0, n.doubleValue() / 100.0)) : 1f;
                return Boolean.TRUE;
            // Rain on the Windscreen: strength in percent (the shader itself says the card is on)
            case "windscreen":
                Windscreen.amount = a instanceof Number n ? (float) Math.max(0.0, Math.min(2.0, n.doubleValue() / 100.0)) : 1f;
                return Boolean.TRUE;
            // Moodle Effects: strength in percent (the shader itself says the card is on)
            case "moodleFx":
                MoodleFx.amount = a instanceof Number n ? (float) Math.max(0.0, Math.min(2.0, n.doubleValue() / 100.0)) : 1f;
                return Boolean.TRUE;
            // Breath and Exhaust in the Cold: amount in percent (the shader itself says the card is on)
            case "breath":
                Breath.amount = a instanceof Number n ? (float) Math.max(0.0, Math.min(2.0, n.doubleValue() / 100.0)) : 1f;
                return Boolean.TRUE;
            // Better Puddles' scene reflection in percent (the puddle shader itself says it's on)
            case "puddleScene":
                PuddleSplash.sceneStrength = a instanceof Number n ? (float) Math.max(0.0, Math.min(1.0, n.doubleValue() / 100.0)) : 0f;
                return Boolean.TRUE;
            // Specular Highlights: strength and dry surfaces' gloss in percent (the shader itself says the card is on)
            case "specular":
                PixelLight.specStrength = a instanceof Number n ? (float) Math.max(0.0, Math.min(2.0, n.doubleValue() / 100.0)) : 1f;
                PixelLight.specDry = b instanceof Number n2 ? (float) Math.max(0.0, Math.min(0.5, n2.doubleValue() / 100.0)) : 0f;
                return Boolean.TRUE;
            // Muzzle Flashes and Explosions: strength in percent (the shader itself says the card is on)
            case "bursts":
                FlashLights.amount = a instanceof Number n ? (float) Math.max(0.0, Math.min(2.0, n.doubleValue() / 100.0)) : 1f;
                return Boolean.TRUE;
            // An explosion (kind 1) or molotov (2) from Lua's OnThrowableExplode: a = kind * 100 + level + 50 (basements are below 0), b = x, c = y
            case "burst":
                if (a instanceof Number k && b instanceof Number x && c instanceof Number y) {
                    int kz = k.intValue();
                    FlashLights.event(Math.floorDiv(kz, 100), x.floatValue(), y.floatValue(), Math.floorMod(kz, 100) - 50);
                }
                return Boolean.TRUE;
            // Fog quality steps, for the cached fog march (the shader has them as a define)
            case "waterMist":
                FogField.waterWanted = Boolean.TRUE.equals(a);
                return true;
            case "fogSteps":
                SkyCache.scatterSamples = a instanceof Number n ? Math.max(4, Math.min(8, n.intValue())) : 6;
                return Boolean.TRUE;
            // Sun through windows (Sun and Cloud Shadows): strength in percent (the shader itself says it's on)
            case "sunRooms":
                PixelLight.sunRoomsStrength = a instanceof Number n ? (float) Math.max(0.0, Math.min(2.0, n.doubleValue() / 100.0)) : 0.6f;
                return Boolean.TRUE;
            case "godRays":
                PixelLight.godRaysStrength = a instanceof Number n ? (float) Math.max(0.5, Math.min(2.0, n.doubleValue() / 100.0)) : 1f;
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
            // Better Rain and Snow's leaves and blowing snow, in percent (0 = off)
            case "precipExtras":
                Precipitation.extras = a instanceof Number n ? (float) Math.max(0.0, Math.min(2.0, n.doubleValue() / 100.0)) : 0f;
                return Boolean.TRUE;
            case "precip":
                Precipitation.configure(Boolean.TRUE.equals(a), b instanceof Number n ? n.intValue() : 2, c instanceof Number m ? m.doubleValue() : 100.0);
                return Boolean.TRUE;
            // PZOE weather MP sync: the server's Lua asks for the state to send, the client's Lua hands it over
            case "weatherState":
                return WeatherCore.encodeForClients();
            case "weatherStateIfChanged":
                return WeatherCore.encodeIfChanged();
            // Shovel (PZOE_SnowShovel.lua): how deep the snow lies on a square (levels), clear one, the list for joiners
            case "snowHere":
                return Double.valueOf(a instanceof Number x && b instanceof Number y
                    ? com.pzoptimizer.pzoe.weather.WeatherSnow.depthAt(x.floatValue() + 0.5f, y.floatValue() + 0.5f, 0f) : 0.0);
            case "snowClear":
                if (a instanceof Number x && b instanceof Number y) com.pzoptimizer.pzoe.weather.WeatherSnow.clear(x.intValue(), y.intValue());
                return Boolean.TRUE;
            case "snowClearedList":
                return com.pzoptimizer.pzoe.weather.WeatherSnow.clearedList();
            // Melted patches round fires and snow piles: the server's list for joiners, and a client taking it
            case "snowMarks":
                return com.pzoptimizer.pzoe.weather.WeatherSnow.marksText();
            case "snowMarksApply":
                if (a != null) com.pzoptimizer.pzoe.weather.WeatherSnow.applyMarks(a.toString());
                return Boolean.TRUE;
            // Dev menu: force a kind of weather for b game hours (SP/server), returns what happened
            case "weatherForce":
                return com.pzoptimizer.pzoe.weather.WeatherEvents.force(a == null ? "" : a.toString(), b instanceof Number n ? n.doubleValue() : 2.0);
            // Radio forecast: the PZOE weather events on a day (0 today, 1 tomorrow), "FogBank,Gale"
            case "weatherForecast":
                return com.pzoptimizer.pzoe.weather.WeatherEvents.forecast(a instanceof Number n ? n.intValue() : 0);
            case "weatherApply":
                WeatherCore.applyRemote(a == null ? "" : a.toString());
                return Boolean.TRUE;
            case "weather":
                return WeatherCore.stateText();
            // Living Weather card: look-only extras on/off and gust strength (percent)
            case "weatherLook":
                com.pzoptimizer.pzoe.weather.WeatherFronts.configure(Boolean.TRUE.equals(a), b instanceof Number n ? n.doubleValue() : 100.0);
                return Boolean.TRUE;
            // Ground Blood card: quality 1-3 (texels a square; the shader itself says the card is on)
            case "goreEnabled":
                return Boolean.TRUE;
            case "goreGround":
                GoreMap.configure(a instanceof Number n ? n.intValue() : 2);
                return Boolean.TRUE;
            case "goreSaveSurfaces":
                GoreMap.saveSurfaces();
                return Boolean.TRUE;
            // The other Gore cards: "spray=1;gibs=0;amount=100;..."
            case "gore":
                GoreFx.configure(a == null ? null : a.toString());
                return Boolean.TRUE;
            // Gore gameplay in MP: the server's Lua takes limb losses to send, the client's Lua hands them over
            case "goreEvents":
                return GoreCore.takeOutbox();
            case "goreApply":
                GoreCore.applyRemote(a == null ? "" : a.toString());
                return Boolean.TRUE;
            case "fireExposure":
                return a instanceof zombie.characters.IsoPlayer p ? SmokeExposure.pulse(p, Boolean.TRUE.equals(b)) : null;
            case "fireExposureRemote":
                if (zombie.network.GameClient.client && a instanceof zombie.characters.IsoPlayer p && b instanceof Number severity)
                    SmokeExposure.setLocal(p, severity.intValue());
                return Boolean.TRUE;
            case "fireTopology":
                if (a instanceof zombie.iso.IsoGridSquare square) SmokeOpenings.invalidate(square);
                return Boolean.TRUE;
            case "fireSoot":
                if (a instanceof zombie.iso.IsoGridSquare square) Scorch.depositSoot(square);
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
