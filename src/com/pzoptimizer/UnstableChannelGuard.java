package com.pzoptimizer;

/**
 * PZO Unstable Channel Gatekeeper.
 * Enforces strict channel isolation:
 */
public final class UnstableChannelGuard {

    private static volatile Boolean cachedIsUnstable = null;

    public static boolean isUnstableBuild() {
        if (cachedIsUnstable != null) {
            return cachedIsUnstable;
        }

        String ver = UpdateChecker.CURRENT_VERSION.toLowerCase();
        boolean unstable = ver.contains("unstable") || ver.contains("beta") || ver.contains("dev")
                || ver.contains("preview") || PZOConfig.isBetaOptIn();

        cachedIsUnstable = unstable;
        return unstable;
    }

    public static void resetCache() {
        cachedIsUnstable = null;
    }
}
