package com.zenskar.billing.domain;

/**
 * Decides whether an endpoint is HEALTHY or UNHEALTHY from its recent outcome
 * window. Unhealthy when failures in the window reach {@code failureThreshold},
 * or when trailing consecutive failures reach {@code consecutiveThreshold} —
 * either signal catches both high-volume and low-volume failing endpoints.
 */
public final class HealthPolicy {

    private final int windowSize;
    private final int failureThreshold;
    private final int consecutiveThreshold;

    public HealthPolicy(int windowSize, int failureThreshold, int consecutiveThreshold) {
        if (windowSize < 1) throw new IllegalArgumentException("windowSize >= 1");
        if (failureThreshold < 1 || failureThreshold > windowSize) {
            throw new IllegalArgumentException("failureThreshold in [1, windowSize]");
        }
        if (consecutiveThreshold < 1) throw new IllegalArgumentException("consecutiveThreshold >= 1");
        this.windowSize = windowSize;
        this.failureThreshold = failureThreshold;
        this.consecutiveThreshold = consecutiveThreshold;
    }

    public int windowSize() {
        return windowSize;
    }

    public EndpointHealth evaluate(int recentFailures, int consecutiveFailures) {
        return recentFailures >= failureThreshold || consecutiveFailures >= consecutiveThreshold
                ? EndpointHealth.UNHEALTHY
                : EndpointHealth.HEALTHY;
    }
}
