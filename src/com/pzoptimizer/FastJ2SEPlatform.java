package com.pzoptimizer;

import se.krka.kahlua.j2se.J2SEPlatform;
import se.krka.kahlua.j2se.KahluaTableImpl;
import se.krka.kahlua.vm.KahluaTable;

/**
 * Project Zomboid Build 42 - High-Speed J2SEPlatform Provider.
 * Overrides newTable() to construct KahluaTableImpl instances backed by FastLuaMap,
 * accelerating Lua list and array operations across the entire game engine.
 */
public class FastJ2SEPlatform extends J2SEPlatform {

    private static final FastJ2SEPlatform INSTANCE = new FastJ2SEPlatform();

    public static FastJ2SEPlatform getInstance() {
        return INSTANCE;
    }

    @Override
    public KahluaTable newTable() {
        return new KahluaTableImpl(new FastLuaMap());
    }
}
