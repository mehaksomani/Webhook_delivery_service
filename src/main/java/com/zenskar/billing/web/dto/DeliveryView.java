package com.zenskar.billing.web.dto;

import java.time.Instant;
import java.util.List;

import com.zenskar.billing.domain.AttemptOutcome;
import com.zenskar.billing.domain.Delivery;
import com.zenskar.billing.domain.DeliveryStatus;

/**
 * HTTP-facing view of a Delivery. Includes attempt history so the runbook
 * can see exactly what happened to a flagged event.
 */
public record DeliveryView(
        String eventId,
        String endpointId,
        String eventType,
        DeliveryStatus status,
        int attemptCount,
        Instant submittedAt,
        Instant nextAttemptAt,
        Instant succeededAt,
        Instant deadLetteredAt,
        String deadLetterReason,
        List<AttemptView> attempts
) {

    public record AttemptView(
            int attemptNo,
            Instant startedAt,
            Instant finishedAt,
            AttemptOutcome outcome,
            Integer statusCode,
            Long latencyMs,
            String errorMessage
    ) {}

    public static DeliveryView of(Delivery d) {
        List<AttemptView> attemptViews = d.getAttempts().stream()
                .map(a -> new AttemptView(
                        a.getAttemptNo(),
                        a.getStartedAt(),
                        a.getFinishedAt(),
                        a.getOutcome(),
                        a.getStatusCode(),
                        a.getLatencyMs(),
                        a.getErrorMessage()))
                .toList();
        return new DeliveryView(
                d.getEventId(),
                d.getEndpointId(),
                d.getEventType(),
                d.getStatus(),
                d.getAttemptCount(),
                d.getSubmittedAt(),
                d.getNextAttemptAt(),
                d.getSucceededAt(),
                d.getDeadLetteredAt(),
                d.getDeadLetterReason(),
                attemptViews
        );
    }
}
