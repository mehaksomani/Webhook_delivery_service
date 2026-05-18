package com.zenskar.billing.service;

/**
 * Input to {@code SubmitService.submit}. The four fields named in the behavioral spec.
 * No timestamps, no internal state — the service assigns those.
 */
public record SubmitEventCommand(
        String eventId,
        String eventType,
        String endpointId,
        String payload
) {}
