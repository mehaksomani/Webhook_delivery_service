package com.zenskar.billing.events;

import java.time.Instant;

import com.zenskar.billing.domain.AttemptOutcome;

/** Fired after an attempt fails (retriable or permanent). Health service listens for it. */
public record DeliveryFailedEvent(
        String eventId,
        String endpointId,
        int attemptNo,
        AttemptOutcome outcome,
        Integer statusCode,
        Instant failedAt
) {}
