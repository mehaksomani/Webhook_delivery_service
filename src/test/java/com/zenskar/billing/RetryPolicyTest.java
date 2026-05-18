package com.zenskar.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.zenskar.billing.domain.RetryPolicy;

/**
 * Unit tests for the retry-delay arithmetic. The scenario tests use zero
 * delays to keep CI fast; this class verifies the actual schedule and
 * jitter bounds so the documented behavior in design.md is enforced.
 */
class RetryPolicyTest {

    @Test
    void schedule_is_consumed_in_order() {
        var policy = new RetryPolicy(5, List.of(1L, 5L, 25L, 120L, 600L), 0.0);
        assertThat(policy.nextDelay(1)).hasValue(Duration.ofSeconds(1));
        assertThat(policy.nextDelay(2)).hasValue(Duration.ofSeconds(5));
        assertThat(policy.nextDelay(3)).hasValue(Duration.ofSeconds(25));
        assertThat(policy.nextDelay(4)).hasValue(Duration.ofSeconds(120));
    }

    @Test
    void no_delay_when_max_attempts_reached() {
        var policy = new RetryPolicy(5, List.of(1L, 5L, 25L, 120L, 600L), 0.0);
        assertThat(policy.nextDelay(5)).isEmpty();
        assertThat(policy.nextDelay(6)).isEmpty();
    }

    @Test
    void shorter_schedule_clamps_to_last_entry() {
        var policy = new RetryPolicy(10, List.of(1L, 5L), 0.0);
        assertThat(policy.nextDelay(1)).hasValue(Duration.ofSeconds(1));
        assertThat(policy.nextDelay(2)).hasValue(Duration.ofSeconds(5));
        // Beyond the schedule length, last entry is reused (capped backoff).
        assertThat(policy.nextDelay(3)).hasValue(Duration.ofSeconds(5));
        assertThat(policy.nextDelay(9)).hasValue(Duration.ofSeconds(5));
    }

    @Test
    void jitter_stays_within_documented_bounds() {
        var policy = new RetryPolicy(3, List.of(10L), 0.2);
        // Multiplicative jitter of ±20%: 8s to 12s in milliseconds (with random factor).
        for (int i = 0; i < 200; i++) {
            Optional<Duration> d = policy.nextDelay(1);
            assertThat(d).isPresent();
            assertThat(d.get().toMillis())
                    .isBetween(8_000L, 12_001L);
        }
    }

    @Test
    void zero_jitter_returns_exact_base_delay() {
        var policy = new RetryPolicy(3, List.of(7L), 0.0);
        for (int i = 0; i < 10; i++) {
            assertThat(policy.nextDelay(1)).hasValue(Duration.ofSeconds(7));
        }
    }
}
