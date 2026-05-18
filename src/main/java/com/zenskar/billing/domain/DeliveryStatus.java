package com.zenskar.billing.domain;

/**
 * Lifecycle of a Delivery.
 * <p>
 * PENDING: scheduled for dispatch; waiting for a worker (or for next_attempt_at to arrive).
 * IN_FLIGHT: a worker has leased it and is executing an HTTP attempt right now.
 * SUCCEEDED: terminal. The receiver returned 2xx on some attempt.
 * DEAD_LETTERED: terminal. Permanent failure or exhausted retries.
 */
public enum DeliveryStatus {
    PENDING,
    IN_FLIGHT,
    SUCCEEDED,
    DEAD_LETTERED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == DEAD_LETTERED;
    }
}
