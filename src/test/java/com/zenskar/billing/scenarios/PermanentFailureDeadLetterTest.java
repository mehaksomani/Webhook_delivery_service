package com.zenskar.billing.scenarios;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.zenskar.billing.domain.Delivery;
import com.zenskar.billing.domain.DeliveryStatus;

/**
 * S3 — Permanent failure goes to dead letter.
 * Setup: one endpoint that always returns 500. Submit one event.
 * Expected: after the configured max attempts, the event becomes DEAD_LETTERED
 * and retries stop. The dead-lettered delivery remains queryable.
 * <p>
 * The test-time config sets billing.retry.max-attempts=4 (see test
 * application.properties). After 4 retriable failures the delivery dead-letters.
 */
class PermanentFailureDeadLetterTest extends AbstractScenarioTest {

    @Test
    void exhausted_retries_move_to_dead_letter() {
        wireMock.stubFor(post(urlEqualTo("/s3")).willReturn(aResponse().withStatus(500)));

        registerEndpoint("ep-s3", "/s3");
        Instant before = Instant.now().minusSeconds(1);
        submit("evt-s3-1", "ep-s3");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Delivery d = queryService.findDelivery("evt-s3-1").orElseThrow();
            assertThat(d.getStatus()).isEqualTo(DeliveryStatus.DEAD_LETTERED);
            assertThat(d.getAttemptCount()).isEqualTo(4);
            assertThat(d.getDeadLetterReason()).contains("max_attempts_exceeded");
        });

        // No retries beyond max — verify HTTP attempt count matches.
        wireMock.verify(4, postRequestedFor(urlEqualTo("/s3")));

        // S9 contract: must remain queryable as dead-lettered.
        assertThat(queryService.deadLettersSince(before))
                .extracting(Delivery::getEventId)
                .containsExactly("evt-s3-1");
    }

    @Test
    void permanent_4xx_dead_letters_immediately_without_burning_retries() {
        wireMock.stubFor(post(urlEqualTo("/s3-perm")).willReturn(aResponse().withStatus(400)));

        registerEndpoint("ep-s3-perm", "/s3-perm");
        submit("evt-s3-perm", "ep-s3-perm");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Delivery d = queryService.findDelivery("evt-s3-perm").orElseThrow();
            assertThat(d.getStatus()).isEqualTo(DeliveryStatus.DEAD_LETTERED);
            assertThat(d.getAttemptCount()).isEqualTo(1);
            assertThat(d.getDeadLetterReason()).contains("permanent_failure");
        });

        wireMock.verify(1, postRequestedFor(urlEqualTo("/s3-perm")));
    }
}
