package com.zenskar.billing.domain;

import java.time.Duration;

import com.zenskar.billing.domain.EndpointHealth;

/**
 * Decides an endpoint's health from its recent outcome window.
 * <p>
 * Inputs the caller provides:
 *  - {@code recentFailures}: number of failing attempts in the last {@code windowSize}.
 *  - {@code consecutiveFailures}: trailing consecutive failures (resets on any success).
 *  - {@code current}: current health, for hysteresis decisions.
 * <p>
 * Output: a target {@code EndpointHealth} and (if TRIPPED) how long to stay tripped.
 */
public final class HealthPolicy {

    public record Decision(EndpointHealth target, Duration cooldown) {
        public static Decision healthy() {
            return new Decision(EndpointHealth.HEALTHY, Duration.ZERO);
        }
        public static Decision degraded() {
            return new Decision(EndpointHealth.DEGRADED, Duration.ZERO);
        }
        public static Decision tripped(Duration cooldown) {
            return new Decision(EndpointHealth.TRIPPED, cooldown);
        }
    }

    private final int windowSize;
    private final int degradedThreshold;
    private final int trippedThreshold;
    private final int trippedConsecutive;
    private final Duration trippedCooldown;

    public HealthPolicy(int windowSize, int degradedThreshold, int trippedThreshold,
                        int trippedConsecutive, Duration trippedCooldown) {
        if (windowSize < 1) throw new IllegalArgumentException("windowSize >= 1");
        if (degradedThreshold < 1 || degradedThreshold > windowSize) {
            throw new IllegalArgumentException("degradedThreshold in [1, windowSize]");
        }
        if (trippedThreshold < degradedThreshold || trippedThreshold > windowSize) {
            throw new IllegalArgumentException("trippedThreshold in [degradedThreshold, windowSize]");
        }
        if (trippedConsecutive < 1) throw new IllegalArgumentException("trippedConsecutive >= 1");
        this.windowSize = windowSize;
        this.degradedThreshold = degradedThreshold;
        this.trippedThreshold = trippedThreshold;
        this.trippedConsecutive = trippedConsecutive;
        this.trippedCooldown = trippedCooldown;
    }

    public int windowSize() {
        return windowSize;
    }

    public Decision evaluate(int recentFailures, int consecutiveFailures) {
        if (consecutiveFailures >= trippedConsecutive || recentFailures >= trippedThreshold) {
            return Decision.tripped(trippedCooldown);
        }
        if (recentFailures >= degradedThreshold) {
            return Decision.degraded();
        }
        return Decision.healthy();
    }
}
