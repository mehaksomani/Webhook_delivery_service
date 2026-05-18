package com.zenskar.billing.http;

import com.zenskar.billing.domain.AttemptOutcome;

/**
 * Outcome of a single HTTP attempt against a billing endpoint.
 * The status code is null if the request never produced an HTTP response
 * (connection error, timeout, etc.) — in that case look at {@code error}.
 */
public record HttpDeliveryResult(
        AttemptOutcome outcome,
        Integer statusCode,
        long latencyMs,
        String error
) {

    public static HttpDeliveryResult success(int status, long latencyMs) {
        return new HttpDeliveryResult(AttemptOutcome.SUCCESS, status, latencyMs, null);
    }

    public static HttpDeliveryResult httpError(int status, long latencyMs) {
        AttemptOutcome o = AttemptOutcome.fromStatus(status);
        return new HttpDeliveryResult(o, status, latencyMs, "HTTP " + status);
    }

    public static HttpDeliveryResult timeout(long latencyMs) {
        return new HttpDeliveryResult(AttemptOutcome.RETRIABLE_FAILURE, null, latencyMs, "timeout");
    }

    public static HttpDeliveryResult ioError(long latencyMs, String message) {
        return new HttpDeliveryResult(AttemptOutcome.RETRIABLE_FAILURE, null, latencyMs, message);
    }
}
