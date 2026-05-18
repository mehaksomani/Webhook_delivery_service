package com.zenskar.billing.scenarios;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.zenskar.billing.domain.DeliveryStatus;

/**
 * S4 — Endpoint isolation. ⭐ Gate.
 * <p>
 * Setup:
 *   - Endpoint A always succeeds, but takes ~5 seconds per response.
 *   - Endpoint B always succeeds, in ~50ms.
 *   - Submit 10 events to A and 10 events to B at t=0.
 * <p>
 * Expected:
 *   By t=2s, all 10 of B's events have a delivery_succeeded record. A's are
 *   still in flight. B's deliveries must NOT wait for A's deliveries.
 * <p>
 * Why this passes single-threaded: the scheduler thread fires HTTP requests
 * with {@code sendAsync}; the slow request sits in the JDK HttpClient's I/O
 * loop without blocking the scheduler from servicing other endpoints. The
 * per-endpoint in-flight cap (4 by default) also prevents endpoint A from
 * monopolizing the scheduler's per-tick batch.
 */
class EndpointIsolationTest extends AbstractScenarioTest {

    @Test
    void slow_endpoint_does_not_block_fast_endpoint() {
        wireMock.stubFor(post(urlEqualTo("/A"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(5_000)));
        wireMock.stubFor(post(urlEqualTo("/B"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(50)));

        registerEndpoint("ep-A", "/A");
        registerEndpoint("ep-B", "/B");

        for (int i = 0; i < 10; i++) {
            submit("evt-A-" + i, "ep-A");
            submit("evt-B-" + i, "ep-B");
        }

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            for (int i = 0; i < 10; i++) {
                String id = "evt-B-" + i;
                assertThat(queryService.findDelivery(id).orElseThrow().getStatus())
                        .as("B event %s should be SUCCEEDED within 3s", id)
                        .isEqualTo(DeliveryStatus.SUCCEEDED);
            }
        });

        // Sanity check: at this point A's deliveries are still in flight or pending,
        // not yet succeeded. Some may be IN_FLIGHT (claimed), others still PENDING.
        for (int i = 0; i < 10; i++) {
            DeliveryStatus aStatus = queryService.findDelivery("evt-A-" + i).orElseThrow().getStatus();
            assertThat(aStatus)
                    .as("A event %d should NOT be SUCCEEDED yet", i)
                    .isIn(DeliveryStatus.PENDING, DeliveryStatus.IN_FLIGHT);
        }

        wireMock.verify(10, postRequestedFor(urlEqualTo("/B")));
    }
}
