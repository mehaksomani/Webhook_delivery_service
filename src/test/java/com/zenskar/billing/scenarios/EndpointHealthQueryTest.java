package com.zenskar.billing.scenarios;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.zenskar.billing.domain.EndpointHealth;

/**
 * S8 — Endpoint health query.
 * The query returns a status that changes observably with delivery outcomes:
 * HEALTHY or UNHEALTHY (documented in design.md).
 */
class EndpointHealthQueryTest extends AbstractScenarioTest {

    @Test
    void endpoint_health_transitions_with_outcomes() {
        // A registered endpoint with no traffic starts HEALTHY.
        registerEndpoint("ep-s8", "/s8");
        assertThat(queryService.endpointStatus("ep-s8")).isEqualTo(EndpointHealth.HEALTHY);

        // Fail every request so the endpoint turns UNHEALTHY.
        wireMock.stubFor(post(urlEqualTo("/s8")).willReturn(aResponse().withStatus(500)));

        for (int i = 0; i < 6; i++) {
            submit("evt-s8-" + i, "ep-s8");
        }

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(queryService.endpointStatus("ep-s8"))
                        .as("endpoint should be UNHEALTHY after repeated failures")
                        .isEqualTo(EndpointHealth.UNHEALTHY));
    }

    @Test
    void successful_delivery_keeps_endpoint_healthy() {
        wireMock.stubFor(post(urlEqualTo("/s8h")).willReturn(aResponse().withStatus(200)));
        registerEndpoint("ep-s8h", "/s8h");
        submit("evt-s8h-1", "ep-s8h");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(queryService.endpointStatus("ep-s8h")).isEqualTo(EndpointHealth.HEALTHY));
    }
}
