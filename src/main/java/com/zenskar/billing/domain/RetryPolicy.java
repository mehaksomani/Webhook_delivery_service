package com.zenskar.billing.domain;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
 */
public final class RetryPolicy {

    private final int maxAttempts;
    private final List<Long> scheduleSeconds;

    public RetryPolicy(int maxAttempts, List<Long> scheduleSeconds) {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts >= 1");
        this.maxAttempts = maxAttempts;
        this.scheduleSeconds = List.copyOf(Objects.requireNonNull(scheduleSeconds));
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Optional<Duration> nextDelay(int completedAttemptNo) {
        if (completedAttemptNo >= maxAttempts) return Optional.empty();
        int idx = Math.min(completedAttemptNo - 1, scheduleSeconds.size() - 1);
        if (idx < 0) idx = 0;
        return Optional.of(Duration.ofSeconds(scheduleSeconds.get(idx)));
    }
}
