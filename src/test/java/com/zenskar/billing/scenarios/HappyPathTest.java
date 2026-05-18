package com.zenskar.billing.scenarios;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.zenskar.billing.domain.Delivery;
import com.zenskar.billing.domain.DeliveryStatus;

/**
 * S1 — Happy path delivery.
 * Setup: one endpoint that always returns 200. Submit one event.
 * Expected: one HTTP attempt, delivery_succeeded, total_attempts=1.
 */
class HappyPathTest extends AbstractScenarioTest {

    @Test
    void single_attempt_succeeds_on_2xx() {
        wireMock.stubFor(post(urlEqualTo("/s1")).willReturn(aResponse().withStatus(200)));
        registerEndpoint("ep-s1", "/s1");
        submit("evt-s1-1", "ep-s1");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Delivery d = queryService.findDelivery("evt-s1-1").orElseThrow();
            assertThat(d.getStatus()).isEqualTo(DeliveryStatus.SUCCEEDED);
            assertThat(d.getAttemptCount()).isEqualTo(1);
        });
        wireMock.verify(1, postRequestedFor(urlEqualTo("/s1")));
    }
}
