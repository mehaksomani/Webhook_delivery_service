package com.zenskar.billing.http;

import java.util.concurrent.CompletableFuture;

/**
 * Sends a single webhook delivery attempt and reports the outcome.
 * <p>
 * An abstraction over the outbound HTTP call so the dispatch pipeline depends on
 * a capability, not on the JDK {@code HttpClient}: tests can inject a fake, and a
 * different transport (a pooled client, a signed-request client, a mock) can be
 * swapped in without touching the scheduler. The returned future never completes
 * exceptionally for an HTTP-level failure — every result, including timeouts and
 * blocked targets, is reported as an {@link HttpDeliveryResult}.
 */
public interface WebhookClient {

    CompletableFuture<HttpDeliveryResult> deliver(
            String url,
            String eventId,
            String eventType,
            int attemptNo,
            String payload);
}
