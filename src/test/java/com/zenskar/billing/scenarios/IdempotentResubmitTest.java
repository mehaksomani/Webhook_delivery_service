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
import com.zenskar.billing.service.SubmitEventCommand;

/**
 * S5 — Idempotency on resubmission.
 * Setup: endpoint always returns 200. Submit the same event_id twice.
 * Expected: the receiver sees exactly one POST; second submit() is a no-op.
 */
class IdempotentResubmitTest extends AbstractScenarioTest {

    @Test
    void same_event_id_twice_yields_one_http_post() {
        wireMock.stubFor(post(urlEqualTo("/s5")).willReturn(aResponse().withStatus(200)));
        registerEndpoint("ep-s5", "/s5");

        SubmitEventCommand cmd = new SubmitEventCommand("evt-s5-dup", "test.event", "ep-s5", "{}");
        Delivery first = submitService.submit(cmd);
        Delivery second = submitService.submit(cmd);

        // Same row.
        assertThat(second.getEventId()).isEqualTo(first.getEventId());
        assertThat(deliveryRepository.count()).isEqualTo(1);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Delivery d = queryService.findDelivery("evt-s5-dup").orElseThrow();
            assertThat(d.getStatus()).isEqualTo(DeliveryStatus.SUCCEEDED);
            assertThat(d.getAttemptCount()).isEqualTo(1);
        });

        wireMock.verify(1, postRequestedFor(urlEqualTo("/s5")));

        // A third submission, even after success, is still a no-op (does not re-deliver).
        Delivery third = submitService.submit(cmd);
        assertThat(third.getEventId()).isEqualTo("evt-s5-dup");
        assertThat(deliveryRepository.count()).isEqualTo(1);
        wireMock.verify(1, postRequestedFor(urlEqualTo("/s5")));
    }
}
