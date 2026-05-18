package com.zenskar.billing.scenarios;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.zenskar.billing.domain.AttemptOutcome;
import com.zenskar.billing.domain.Delivery;
import com.zenskar.billing.domain.DeliveryStatus;

/**
 * S2 — Retry with backoff on transient failure.
 * Setup: endpoint returns 500 on attempts 1 and 2, 200 on attempt 3.
 * Expected: 3 HTTP attempts, succeeded on the 3rd, attempts spaced by retry policy.
 * <p>
 * The test-time retry config (see src/test/resources/application.properties)
 * uses zero-second delays so attempts are spaced only by the poll interval —
 * we are testing the retry mechanism, not the actual delay arithmetic. Delay
 * arithmetic is covered by RetryPolicyTest (pure unit test).
 */
class RetryWithBackoffTest extends AbstractScenarioTest {

    @Test
    void retries_until_2xx_then_succeeds() {
        String scenario = "s2-retry";
        wireMock.stubFor(post(urlEqualTo("/s2")).inScenario(scenario)
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("after-1"));
        wireMock.stubFor(post(urlEqualTo("/s2")).inScenario(scenario)
                .whenScenarioStateIs("after-1")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("after-2"));
        wireMock.stubFor(post(urlEqualTo("/s2")).inScenario(scenario)
                .whenScenarioStateIs("after-2")
                .willReturn(aResponse().withStatus(200)));

        registerEndpoint("ep-s2", "/s2");
        submit("evt-s2-1", "ep-s2");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Delivery d = queryService.findDelivery("evt-s2-1").orElseThrow();
            assertThat(d.getStatus()).isEqualTo(DeliveryStatus.SUCCEEDED);
            assertThat(d.getAttemptCount()).isEqualTo(3);
            assertThat(d.getAttempts())
                    .extracting("outcome")
                    .containsExactly(AttemptOutcome.RETRIABLE_FAILURE, AttemptOutcome.RETRIABLE_FAILURE, AttemptOutcome.SUCCESS);
        });
        wireMock.verify(3, postRequestedFor(urlEqualTo("/s2")));
    }
}
