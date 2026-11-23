package com.pzoptimizer.pzoe.water;

import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWaterFlow;
import zombie.iso.SpriteDetails.IsoFlagType;

public final class WaterSurface {
    private WaterSurface() {}

    public static float liquidDepthAt(IsoGridSquare square, float x, float y) {
        float depth = depthAt(square, x, y);
        return depth > 0f && WaterIce.supports(square) ? 0f : depth;
    }

    public static float depthAt(IsoGridSquare square, float x, float y) {
        if (square == null || square.z != 0 || square.hasFloorOverWater()) return 0f;
        if (square.has(IsoFlagType.water)) return 1f;
        var floor = square.getFloor();
        var sprite = floor == null ? null : floor.getSprite();
        String name = sprite == null ? null : sprite.getName();
        if (name == null || !name.startsWith("blends_natural") || IsoWaterFlow.getShore(square.x, square.y) != 1) {
            return 0f;
        }
        // Sample the same shore corners as IsoWaterGeometry without initializing or mutating its render cache.
        var n = square.getAdjacentSquare(IsoDirections.N);
        var s = square.getAdjacentSquare(IsoDirections.S);
        var w = square.getAdjacentSquare(IsoDirections.W);
        var e = square.getAdjacentSquare(IsoDirections.E);
        var nw = square.getAdjacentSquare(IsoDirections.NW);
        var ne = square.getAdjacentSquare(IsoDirections.NE);
        var sw = square.getAdjacentSquare(IsoDirections.SW);
        var se = square.getAdjacentSquare(IsoDirections.SE);
        if (n == null || s == null || w == null || e == null || nw == null || ne == null || sw == null || se == null) {
            return 0f;
        }
        boolean north = n.has(IsoFlagType.water);
        boolean south = s.has(IsoFlagType.water);
        boolean west = w.has(IsoFlagType.water);
        boolean east = e.has(IsoFlagType.water);
        float u = Math.max(0f, Math.min(1f, x - square.x));
        float v = Math.max(0f, Math.min(1f, y - square.y));
        float northDepth = (north || west || nw.has(IsoFlagType.water) ? 1f - u : 0f)
            + (north || east || ne.has(IsoFlagType.water) ? u : 0f);
        float southDepth = (south || west || sw.has(IsoFlagType.water) ? 1f - u : 0f)
            + (south || east || se.has(IsoFlagType.water) ? u : 0f);
        return northDepth + (southDepth - northDepth) * v;
    }
}
