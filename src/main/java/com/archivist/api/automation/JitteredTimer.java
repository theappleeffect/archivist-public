package com.archivist.api.automation;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Utility for humanized, randomized delays during automation.
 * All delays use ThreadLocalRandom for non-deterministic timing.
 */
public final class JitteredTimer {

    private JitteredTimer() {}

    /** Random delay in [minMs, maxMs]. */
    public static int nextDelay(int minMs, int maxMs) {
        return ThreadLocalRandom.current().nextInt(minMs, maxMs + 1);
    }

    /** Occasional idle pause (10% chance, 1-3 seconds). Returns 0 if no pause. */
    public static int nextMovementPause() {
        if (ThreadLocalRandom.current().nextInt(10) == 0) {
            return nextDelay(1000, 3000);
        }
        return 0;
    }

    /** Small random look jitter in degrees (±2). */
    public static float nextLookJitter() {
        return ThreadLocalRandom.current().nextFloat() * 4f - 2f;
    }

    /** Convert milliseconds to ticks (approx). */
    public static int msToTicks(int ms) {
        return Math.max(1, ms / 50);
    }
}
