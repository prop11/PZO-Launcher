package com.pzoptimizer;

public class HordePhysicsOptimizer {

    public static boolean shouldSeparate(float x1, float y1, float x2, float y2, float maxRadius) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float maxRadiusSq = maxRadius * maxRadius;
        float distSq = dx * dx + dy * dy;

        if (distSq <= 0.0001f || distSq >= maxRadiusSq) {
            return false;
        }
        return true;
    }

    public static float calculateRepulsionForce(float distSq, float maxRadius) {
        float invDist = FastMath.fastInvSqrt(distSq);
        float dist = distSq * invDist;
        return (maxRadius - dist) / maxRadius;
    }
}
