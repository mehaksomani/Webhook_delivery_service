package com.zenskar.billing.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import lombok.extern.slf4j.Slf4j;

/**
 * JDK {@link HttpClient}-backed implementation. Uses {@code sendAsync} so each
 * attempt is non-blocking; multiple in-flight attempts to different endpoints
 * share the same I/O loop. Built and wired by {@code HttpClientConfig}.
 */
@Slf4j
public class BillingHttpClient {

    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public BillingHttpClient(HttpClient httpClient, Duration httpRequestTimeout) {
        this.httpClient = httpClient;
        this.requestTimeout = httpRequestTimeout;
    }

    public CompletableFuture<HttpDeliveryResult> deliver(
            String url,
            String eventId,
            String eventType,
            int attemptNo,
            String payload) {

        long startedNanos = System.nanoTime();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("X-Billing-Event-Id", eventId)
                .header("X-Billing-Event-Type", eventType)
                .header("X-Billing-Delivery-Attempt", Integer.toString(attemptNo))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        return httpClient
                .sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .handle((response, throwable) -> {
                    long latencyMs = (System.nanoTime() - startedNanos) / 1_000_000L;
                    if (throwable != null) {
                        Throwable cause = unwrap(throwable);
                        if (cause instanceof HttpTimeoutException) {
                            return HttpDeliveryResult.timeout(latencyMs);
                        }
                        log.debug("HTTP transport error for event_id={} url={} err={}", eventId, url, cause.toString());
                        return HttpDeliveryResult.ioError(latencyMs, cause.getClass().getSimpleName() + ": " + cause.getMessage());
                    }
                    int status = response.statusCode();
                    if (status >= 200 && status < 300) {
                        return HttpDeliveryResult.success(status, latencyMs);
                    }
                    return HttpDeliveryResult.httpError(status, latencyMs);
                });
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        while (cur instanceof CompletionException && cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur;
    }
}
