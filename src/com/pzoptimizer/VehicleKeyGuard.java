package com.pzoptimizer;

import java.lang.reflect.Method;

/**
 * Prefix hook on BaseVehicle.addKeyToGloveBox(). Vanilla dereferences glovebox.container without a null check,
 * which throws for vehicle scripts whose GloveBox part has no container. Only that case is skipped; every
 * other vehicle runs the vanilla body unchanged, building keys and key rings included.
 */
public final class VehicleKeyGuard {

    private static volatile Method getPartById;
    private static volatile Method getItemContainer;

    private VehicleKeyGuard() {}

    public static boolean shouldSkipGloveBoxKey(Object vehicle) {
        try {
            if (getPartById == null) {
                getPartById = vehicle.getClass().getMethod("getPartById", String.class);
            }
            Object glovebox = getPartById.invoke(vehicle, "GloveBox");
            if (glovebox == null) return false;
            if (getItemContainer == null) {
                getItemContainer = glovebox.getClass().getMethod("getItemContainer");
            }
            return getItemContainer.invoke(glovebox) == null;
        } catch (Throwable t) {
            return false;
        }
    }
}
