package com.zenskar.billing.scenarios;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.zenskar.billing.domain.Delivery;
import com.zenskar.billing.domain.DeliveryStatus;

/**
 * S9 — Dead letter listing.
 * {@code dead_letters(since)} returns all dead-lettered events with
 * deadLetteredAt >= since, in deadLetteredAt order.
 */
class DeadLetterListingTest extends AbstractScenarioTest {

    @Test
    void dead_letter_listing_filters_by_since_timestamp() {
        wireMock.stubFor(post(urlEqualTo("/s9-bad")).willReturn(aResponse().withStatus(500)));
        wireMock.stubFor(post(urlEqualTo("/s9-ok")).willReturn(aResponse().withStatus(200)));
        registerEndpoint("ep-s9-bad", "/s9-bad");
        registerEndpoint("ep-s9-ok", "/s9-ok");

        Instant t0 = Instant.now().minusSeconds(1);
        submit("evt-s9-bad-1", "ep-s9-bad");
        submit("evt-s9-bad-2", "ep-s9-bad");
        submit("evt-s9-ok-1", "ep-s9-ok");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(queryService.findDelivery("evt-s9-bad-1").orElseThrow().getStatus())
                    .isEqualTo(DeliveryStatus.DEAD_LETTERED);
            assertThat(queryService.findDelivery("evt-s9-bad-2").orElseThrow().getStatus())
                    .isEqualTo(DeliveryStatus.DEAD_LETTERED);
            assertThat(queryService.findDelivery("evt-s9-ok-1").orElseThrow().getStatus())
                    .isEqualTo(DeliveryStatus.SUCCEEDED);
        });

        // since=t0 returns both dead-letters.
        assertThat(queryService.deadLettersSince(t0))
                .extracting(Delivery::getEventId)
                .containsExactlyInAnyOrder("evt-s9-bad-1", "evt-s9-bad-2");

        // since=now (after dead-lettering) returns nothing.
        assertThat(queryService.deadLettersSince(Instant.now().plusSeconds(60))).isEmpty();
    }
}
