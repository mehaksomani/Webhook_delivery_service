package com.zenskar.billing.scenarios;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.zenskar.billing.domain.AttemptOutcome;
import com.zenskar.billing.domain.Delivery;
import com.zenskar.billing.domain.DeliveryStatus;

/**
 * S6 — Idempotency across worker crash. ⭐ Gate.
 * <p>
 * Setup: endpoint returns 200, but the first response is delayed essentially
 * forever (60s) so the HTTP request sits in flight. We simulate the worker
 * crashing by directly back-dating the row's {@code lease_expires_at} via JDBC
 * — that's what the recovery service watches for. Subsequent responses are
 * immediate. This is more deterministic than racing real wall-clock timers
 * and matches the legacy log's evt_0e64cc0aaece pattern.
 * <p>
 * Expected:
 *  (a) Same Delivery aggregate — attempt 1 marked CRASH, attempt 2 the
 *      success. Not a brand-new row.
 *  (b) Every HTTP request carries a stable {@code X-Billing-Event-Id} header
 *      so receivers can dedupe.
 */
class CrashRecoveryTest extends AbstractScenarioTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void crash_mid_flight_re_dispatches_as_same_event_id_with_stable_header() {
        String sc = "s6-crash";
        wireMock.stubFor(post(urlEqualTo("/s6")).inScenario(sc)
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200).withFixedDelay(60_000))
                .willSetStateTo("after-1"));
        wireMock.stubFor(post(urlEqualTo("/s6")).inScenario(sc)
                .whenScenarioStateIs("after-1")
                .willReturn(aResponse().withStatus(200)));

        registerEndpoint("ep-s6", "/s6");
        submit("evt-s6-1", "ep-s6");

        // Wait for the scheduler to claim it and start attempt 1.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(queryService.findDelivery("evt-s6-1").orElseThrow().getStatus())
                        .isEqualTo(DeliveryStatus.IN_FLIGHT));

        // Simulate the worker crashing mid-flight: back-date the lease so the
        // recovery sweep treats it as stale. The HTTP request is still hanging
        // (60s delay), exactly as it would after a real crash.
        jdbc.update(
                "UPDATE deliveries SET lease_expires_at = ? WHERE event_id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)),
                "evt-s6-1");

        // Wait for recovery + re-dispatch. The second WireMock response is
        // immediate (scenario state moved on), so attempt 2 succeeds fast.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Delivery d = queryService.findDelivery("evt-s6-1").orElseThrow();
            assertThat(d.getStatus()).isEqualTo(DeliveryStatus.SUCCEEDED);
            assertThat(d.getAttemptCount()).isEqualTo(2);
        });

        // (a) Same Delivery aggregate — one row, two attempts.
        Delivery d = queryService.findDelivery("evt-s6-1").orElseThrow();
        assertThat(deliveryRepository.count()).isEqualTo(1);
        assertThat(d.getAttempts())
                .extracting("attemptNo", "outcome")
                .containsExactly(
                        tuple(1, AttemptOutcome.CRASH),
                        tuple(2, AttemptOutcome.SUCCESS));

        // (b) Every HTTP attempt carried the stable X-Billing-Event-Id header.
        wireMock.verify(postRequestedFor(urlEqualTo("/s6"))
                .withHeader("X-Billing-Event-Id", equalTo("evt-s6-1")));
    }
}
