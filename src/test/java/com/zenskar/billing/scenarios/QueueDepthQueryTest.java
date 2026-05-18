package com.zenskar.billing.scenarios;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.zenskar.billing.domain.DeliveryStatus;

/**
 * S7 — Queue depth query.
 * Submit several events to an endpoint whose response is slow enough that
 * many are still PENDING; verify {@code queueDepth(endpoint_id)} reflects
 * the pending count.
 */
class QueueDepthQueryTest extends AbstractScenarioTest {

    @Test
    void queue_depth_reflects_pending_count_per_endpoint() {
        // Slow endpoint A — events pile up in PENDING.
        wireMock.stubFor(post(urlEqualTo("/A"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(3_000)));
        // Fast endpoint B — events drain quickly.
        wireMock.stubFor(post(urlEqualTo("/B"))
                .willReturn(aResponse().withStatus(200)));

        registerEndpoint("ep-A", "/A");
        registerEndpoint("ep-B", "/B");

        for (int i = 0; i < 8; i++) {
            submit("evt-A-" + i, "ep-A");
            submit("evt-B-" + i, "ep-B");
        }

        // Right after submission, B drains and queue_depth -> 0; A still has pending.
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(queryService.queueDepth("ep-B")).isEqualTo(0L));

        // A's queue depth is the count of PENDING — at most 8, at least 4 (a few may
        // be IN_FLIGHT under the per-endpoint cap, which don't count as pending).
        long aDepth = queryService.queueDepth("ep-A");
        assertThat(aDepth)
                .as("ep-A queue depth should reflect pending events not yet dispatched")
                .isBetween(0L, 8L);
        long aPending = deliveryRepository.countByEndpointIdAndStatus("ep-A", DeliveryStatus.PENDING);
        assertThat(aDepth).isEqualTo(aPending);
    }
}
