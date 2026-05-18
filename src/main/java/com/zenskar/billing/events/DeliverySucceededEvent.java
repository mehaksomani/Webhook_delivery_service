package com.zenskar.billing.events;

import java.time.Instant;

/** Fired after a Delivery moves to SUCCEEDED. Health service listens for it. */
public record DeliverySucceededEvent(
        String eventId,
        String endpointId,
        int attemptCount,
        Instant succeededAt
) {}
