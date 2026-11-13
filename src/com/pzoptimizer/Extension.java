package com.pzoptimizer;

import java.util.Set;

/**
 * An engine extension shipped in its own jar with a Workshop mod (PZO Enhancements' PZOE.jar), loaded by Extensions
 * at premain. Its class transforms only exist when the jar is loaded, so players without the mod run none of its code.
 * Everything it touches in PZO is public API: AsmSupport, ShaderPatchHost, PZOLogger, FoliageLod hooks, the
 * ModApi ops it answers.
 */
public interface Extension {
    /** Bumped when anything an extension relies on changes; an extension built for another number is not loaded. */
    int API = 1;

    /** ModApi ops an extension doesn't answer return this, so the next one (or PZO's default) gets them. */
    Object UNHANDLED = new Object();

    int apiVersion();

    String name();

    /** Called once after loading, before any game class is transformed. */
    void init();

    /** Dotted names of classes it patches that can already be loaded at premain (retransformed once). */
    Set<String> earlyTargets();

    /**
     * Runs after PZO's own transform of every class, on PZO's result. Return null to leave the bytes alone. Called for
     * every class load, so it has to bail out on the class name first.
     */
    byte[] transform(ClassLoader loader, String internalName, byte[] bytes);

    /** A PZOApi call from Lua that PZO doesn't handle itself, or UNHANDLED. */
    Object modApi(String op, Object a, Object b, Object c);

    /** Lua shaderCommit: PZO committed its shader patches, commit whatever is staged here too. */
    void commit();

    /** Lua state reset (a new Lua state on load): drop everything mods registered. */
    void reset();
}
