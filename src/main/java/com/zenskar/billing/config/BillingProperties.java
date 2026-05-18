package com.zenskar.billing.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * One properties root for the whole service, bound from {@code billing.*}.
 * Nested records preserve the original property paths
 * ({@code billing.dispatcher.*}, {@code billing.retry.*}, {@code billing.health.*})
 * so the application.properties files stay unchanged.
 */
@ConfigurationProperties(prefix = "billing")
public record BillingProperties(
        Dispatcher dispatcher,
        Retry retry,
        Health health
) {

    public record Dispatcher(
            long pollIntervalMs,
            int batchSize,
            long leaseDurationSeconds,
            int maxInFlightPerEndpoint,
            long httpTimeoutSeconds
    ) {}

    public record Retry(
            int maxAttempts,
            List<Long> scheduleSeconds,
            double jitterRatio
    ) {}

    public record Health(
            int windowSize,
            int degradedFailures,
            int trippedFailures,
            int trippedConsecutive,
            long trippedCooldownSeconds
    ) {}
}
