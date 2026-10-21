package com.pzoptimizer;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The world map (UIWorldMap) spends most of its frame time re-projecting street names and re-running their
 * translation lookups even when the map camera is still. Street label layouts are cached against the map
 * view and reused until it pans, zooms or the map data changes.
 */
public final class WorldMapPerformanceGovernor {

    private static volatile boolean active = true;
    public static final AtomicLong layoutsReused = new AtomicLong(0);

    private static final class ViewState {
        float zoom;
        float centerX;
        float centerY;
        long stamp;
    }

    private static final Map<Object, ViewState> viewStates = new IdentityHashMap<>();
    private static long currentStamp = 1L;

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean value) {
        active = value;
    }

    /**
     * Checks if the map view has changed since the previous layout pass.
     * Returns true if cached layout can be safely reused.
     */
    public static boolean isLayoutReusable(Object mapInstance, float zoom, float cx, float cy) {
        if (!active || mapInstance == null) return false;

        ViewState state = viewStates.get(mapInstance);
        if (state == null) {
            state = new ViewState();
            state.zoom = zoom;
            state.centerX = cx;
            state.centerY = cy;
            state.stamp = currentStamp++;
            viewStates.put(mapInstance, state);
            return false;
        }

        if (Math.abs(state.zoom - zoom) < 1e-4f &&
            Math.abs(state.centerX - cx) < 1e-3f &&
            Math.abs(state.centerY - cy) < 1e-3f) {
            layoutsReused.incrementAndGet();
            return true;
        }

        state.zoom = zoom;
        state.centerX = cx;
        state.centerY = cy;
        state.stamp = currentStamp++;
        return false;
    }

    public static long getViewStamp(Object mapInstance) {
        ViewState state = viewStates.get(mapInstance);
        return state != null ? state.stamp : 0L;
    }

    public static void clear() {
        viewStates.clear();
    }
}
