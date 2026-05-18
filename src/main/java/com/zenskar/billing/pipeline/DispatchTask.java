package com.zenskar.billing.pipeline;

/**
 * A claimed delivery ready for HTTP dispatch. Returned by
 * {@code DispatchService.startAttempt} and consumed by the scheduler.
 * <p>
 * The URL is resolved at claim time from the Endpoint aggregate so a
 * URL change mid-retry does not surprise the caller.
 */
public record DispatchTask(
        String eventId,
        String endpointId,
        String url,
        String eventType,
        int attemptNo,
        String payload
) {}
