package com.zenskar.billing.domain;

/**
 * Health classification of an Endpoint based on its recent delivery outcomes.
 * <p>
 * HEALTHY: deliveries are working normally; no special handling.
 * DEGRADED: some recent failures observed; deliveries still proceed but the endpoint is
 *           visible as at-risk via the query API. No back-off yet — early warning only.
 * TRIPPED: too many failures within the window. Pending deliveries are deferred for a
 *          cooldown period. After cooldown a probe attempt is allowed; success → HEALTHY,
 *          failure → TRIPPED again with another cooldown.
 */
public enum EndpointHealth {
    HEALTHY,
    DEGRADED,
    TRIPPED
}
