package com.zenskar.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.zenskar.billing.domain.RetryPolicy;

/**
 * Unit tests for the retry-delay arithmetic. The scenario tests use short
 * delays to keep CI fast; this class verifies the actual schedule so the
 * documented behavior in design.md is enforced.
 */
class RetryPolicyTest {

    @Test
    void schedule_is_consumed_in_order() {
        var policy = new RetryPolicy(5, List.of(1L, 5L, 25L, 120L, 600L));
        assertThat(policy.nextDelay(1)).hasValue(Duration.ofSeconds(1));
        assertThat(policy.nextDelay(2)).hasValue(Duration.ofSeconds(5));
        assertThat(policy.nextDelay(3)).hasValue(Duration.ofSeconds(25));
        assertThat(policy.nextDelay(4)).hasValue(Duration.ofSeconds(120));
    }

    @Test
    void no_delay_when_max_attempts_reached() {
        var policy = new RetryPolicy(5, List.of(1L, 5L, 25L, 120L, 600L));
        assertThat(policy.nextDelay(5)).isEmpty();
        assertThat(policy.nextDelay(6)).isEmpty();
    }

    @Test
    void shorter_schedule_clamps_to_last_entry() {
        var policy = new RetryPolicy(10, List.of(1L, 5L));
        assertThat(policy.nextDelay(1)).hasValue(Duration.ofSeconds(1));
        assertThat(policy.nextDelay(2)).hasValue(Duration.ofSeconds(5));
        // Beyond the schedule length, last entry is reused (capped backoff).
        assertThat(policy.nextDelay(3)).hasValue(Duration.ofSeconds(5));
        assertThat(policy.nextDelay(9)).hasValue(Duration.ofSeconds(5));
    }
}
