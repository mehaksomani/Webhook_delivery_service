package com.zenskar.billing.events;

import java.time.Instant;

/** Fired after a Delivery moves to DEAD_LETTERED. Observability hook. */
public record DeliveryDeadLetteredEvent(
        String eventId,
        String endpointId,
        int attemptCount,
        String reason,
        Instant deadLetteredAt
) {}
