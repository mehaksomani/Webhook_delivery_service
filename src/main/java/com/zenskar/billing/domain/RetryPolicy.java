package com.zenskar.billing.domain;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Decides whether to retry a delivery and how long to wait before the next attempt.
 * <p>
 * A retry policy is configured with a fixed schedule of delays:
 * <pre>
 *     scheduleSeconds = [1, 5, 25, 120, 600]
 *     maxAttempts     = 5
 * </pre>
 * Attempt 1 happens immediately. After attempt 1 fails retriably,
 * {@code nextDelay(1)} returns 1s, scheduling attempt 2. After attempt 2,
 * {@code nextDelay(2)} returns 5s. And so on. {@code nextDelay(maxAttempts)}
 * returns empty: that delivery is dead-lettered.
 * <p>
 * Jitter is multiplicative: a {@code jitterRatio} of 0.2 means the actual delay
 * is uniformly between {@code base * 0.8} and {@code base * 1.2}. We add jitter
 * to prevent the thundering-herd retry storm when a downstream endpoint recovers
 * after an outage.
 */
public final class RetryPolicy {

    private final int maxAttempts;
    private final List<Long> scheduleSeconds;
    private final double jitterRatio;

    public RetryPolicy(int maxAttempts, List<Long> scheduleSeconds, double jitterRatio) {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts >= 1");
        if (jitterRatio < 0 || jitterRatio > 1) throw new IllegalArgumentException("jitterRatio in [0, 1]");
        this.maxAttempts = maxAttempts;
        this.scheduleSeconds = List.copyOf(Objects.requireNonNull(scheduleSeconds));
        this.jitterRatio = jitterRatio;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Optional<Duration> nextDelay(int completedAttemptNo) {
        if (completedAttemptNo >= maxAttempts) return Optional.empty();
        int idx = Math.min(completedAttemptNo - 1, scheduleSeconds.size() - 1);
        if (idx < 0) idx = 0;
        long baseSeconds = scheduleSeconds.get(idx);
        Duration base = Duration.ofSeconds(baseSeconds);
        return Optional.of(applyJitter(base));
    }

    private Duration applyJitter(Duration base) {
        if (jitterRatio == 0 || base.isZero()) return base;
        double lo = 1.0 - jitterRatio;
        double hi = 1.0 + jitterRatio;
        double factor = ThreadLocalRandom.current().nextDouble(lo, hi);
        long jittered = Math.max(0L, (long) (base.toMillis() * factor));
        return Duration.ofMillis(jittered);
    }
}
