package com.pzoptimizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Project Viewpoint & 3D Camera / Weather Standdown Governor.
 * 
 * Protects game stability and mod compatibility when running mods that modify
 * the 3D projection, camera matrices, or weather shaders (such as Project Viewpoint:
 * True Weathers & Lighting, IsoViewpoint, or PZ3D).
 * 
 * When standdown is active:
 * 1. Bytecode patching for ImprovedFogDrawer is completely bypassed (leaving vanilla/modded shaders untouched).
 * 2. FogQuarterBufferGovernor yields immediately to vanilla/modded fog rendering.
 * 3. RenderFrustumCuller stands down to prevent incorrect 2D screen-space culling of 3D objects.
 * 4. HordeSpatialCuller stands down frustum culling so entities outside 2D bounds are not hidden.
 * 5. RainAndWeatherOptimizer stands down weather masking and puddle elevation overrides.
 */
public final class ViewpointStanddownGovernor {

    public enum Mode {
        AUTO,
        ENABLED,
        DISABLED
    }

    private static volatile Mode currentMode = Mode.AUTO;
    private static volatile boolean modDetected = false;
    private static volatile boolean initialized = false;
    private static volatile long lastCheckTime = 0;

    static {
        initialize();
    }

    private ViewpointStanddownGovernor() {}

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;

        // 1. Read persistent mode from PZOConfig
        try {
            String savedMode = PZOConfig.getViewpointStanddownMode();
            if (savedMode != null && !savedMode.isEmpty()) {
                currentMode = parseMode(savedMode);
            }
        } catch (Throwable ignored) {}

        // 2. Check launch flags and system properties
        checkLaunchParams();

        // 3. Perform initial detection
        checkDetection();
    }

    public static void checkLaunchParams() {
        try {
            String prop = System.getProperty("pzo.viewpoint.standdown");
            if (prop == null) prop = System.getProperty("pzo_viewpoint_standdown");
            if (prop != null) {
                currentMode = parseMode(prop);
            }
        } catch (Throwable ignored) {}
    }

    public static Mode parseMode(String str) {
        if (str == null) return Mode.AUTO;
        String s = str.trim().toUpperCase();
        if ("ENABLED".equals(s) || "FORCE_ON".equals(s) || "ON".equals(s) || "TRUE".equals(s) || "1".equals(s)) {
            return Mode.ENABLED;
        }
        if ("DISABLED".equals(s) || "FORCE_OFF".equals(s) || "OFF".equals(s) || "FALSE".equals(s) || "0".equals(s)) {
            return Mode.DISABLED;
        }
        return Mode.AUTO;
    }

    /**
     * Determines whether Project Viewpoint or related 3D camera / weather mods are present.
     */
    public static boolean checkDetection() {
        long now = System.currentTimeMillis();
        if (modDetected && (now - lastCheckTime < 3000L)) {
            return modDetected;
        }
        lastCheckTime = now;

        boolean detected = false;

        // 1. Check loaded classes for known Viewpoint / True Weathers / 3D mod classes
        String[] modClasses = new String[] {
            "viewpoint.Viewpoint",
            "viewpoint.Camera3D",
            "viewpoint.TrueWeathers",
            "viewpoint.ModEntry",
            "com.viewpoint.ViewpointMain",
            "com.trueweathers.TrueWeathers",
            "viewpoint.TrueWeather"
        };
        for (String cName : modClasses) {
            try {
                Class.forName(cName, false, ViewpointStanddownGovernor.class.getClassLoader());
                detected = true;
                break;
            } catch (Throwable ignored) {}
        }

        // 2. Check PZ ActiveMods list via reflection
        if (!detected) {
            try {
                Class<?> activeModsClass = Class.forName("zombie.modding.ActiveMods");
                Method getByIdMethod = activeModsClass.getMethod("getById", String.class);
                Object defaultMods = getByIdMethod.invoke(null, "default");
                if (defaultMods != null) {
                    Method getModsMethod = defaultMods.getClass().getMethod("getMods");
                    @SuppressWarnings("unchecked")
                    List<String> list = (List<String>) getModsMethod.invoke(defaultMods);
                    if (list != null) {
                        for (String modId : list) {
                            if (isViewpointModId(modId)) {
                                detected = true;
                                break;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        // 3. Check ZomboidFileSystem active mods via reflection
        if (!detected) {
            try {
                Class<?> zfsClass = Class.forName("zombie.ZomboidFileSystem");
                Field instField = zfsClass.getField("instance");
                Object zfs = instField.get(null);
                if (zfs != null) {
                    Method getModIDsMethod = zfsClass.getMethod("getModIDs");
                    @SuppressWarnings("unchecked")
                    List<String> ids = (List<String>) getModIDsMethod.invoke(zfs);
                    if (ids != null) {
                        for (String id : ids) {
                            if (isViewpointModId(id)) {
                                detected = true;
                                break;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (detected != modDetected) {
            modDetected = detected;
            if (detected) {
                PZOLogger.info("[ViewpointStanddownGovernor] Project Viewpoint / 3D camera mod detected! Standdown safeguard active: " + isStanddownActive());
            }
        }

        return modDetected;
    }

    public static boolean isViewpointModId(String modId) {
        if (modId == null) return false;
        String m = modId.toLowerCase();
        return m.contains("viewpoint") || 
               m.contains("trueweathers") || 
               m.contains("true_weathers") || 
               m.contains("projectviewpoint") || 
               m.contains("pz3d") ||
               m.contains("isoviewpoint") ||
               m.contains("3dcamera");
    }

    public static boolean isStanddownActive() {
        if (!initialized) initialize();
        if (currentMode == Mode.ENABLED) return true;
        if (currentMode == Mode.DISABLED) return false;
        // In AUTO mode:
        return modDetected || checkDetection();
    }

    public static boolean isViewpointDetected() {
        return modDetected || checkDetection();
    }

    public static void setViewpointDetected(boolean detected) {
        modDetected = detected;
    }

    public static Mode getMode() {
        return currentMode;
    }

    public static String getStanddownModeString() {
        return currentMode.name();
    }

    public static void setStanddownMode(String modeStr) {
        currentMode = parseMode(modeStr);
        PZOConfig.setViewpointStanddownMode(currentMode.name());
        PZOLogger.info("[ViewpointStanddownGovernor] Standdown mode updated to: " + currentMode.name() + " (Active: " + isStanddownActive() + ")");
    }

    public static void toggleStanddown() {
        if (currentMode == Mode.AUTO) {
            setStanddownMode("ENABLED");
        } else if (currentMode == Mode.ENABLED) {
            setStanddownMode("DISABLED");
        } else {
            setStanddownMode("AUTO");
        }
    }
}
