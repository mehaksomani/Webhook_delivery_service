package com.zenskar.billing.pipeline;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.zenskar.billing.events.DeliveryDeadLetteredEvent;
import com.zenskar.billing.events.DeliveryFailedEvent;
import com.zenskar.billing.events.DeliverySucceededEvent;
import com.zenskar.billing.domain.AttemptOutcome;
import com.zenskar.billing.domain.Delivery;
import com.zenskar.billing.domain.DeliveryAttempt;
import com.zenskar.billing.domain.DeliveryStatus;
import com.zenskar.billing.domain.Endpoint;
import com.zenskar.billing.domain.RetryPolicy;
import com.zenskar.billing.repository.DeliveryRepository;
import com.zenskar.billing.repository.EndpointRepository;
import com.zenskar.billing.config.BillingProperties;
import com.zenskar.billing.http.HttpDeliveryResult;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class DispatchService {

    private final DeliveryRepository deliveryRepository;
    private final EndpointRepository endpointRepository;
    private final RetryPolicy retryPolicy;
    private final ApplicationEventPublisher events;
    private final BillingProperties props;
    private final Clock clock;

    /** Hostname-ish identifier so a runbook can attribute leases to a process. */
    private final String leaseHolderId = "node-" + System.getProperty("user.name", "anon");

    @Transactional
    public Optional<DispatchTask> startAttempt(String eventId) {
        Instant now = Instant.now(clock);
        Instant leaseExpires = now.plusSeconds(props.dispatcher().leaseDurationSeconds());

        int claimed = deliveryRepository.claim(eventId, leaseHolderId, leaseExpires, now);
        if (claimed == 0) {
            // Someone else got it or it was already past PENDING.
            return Optional.empty();
        }

        Delivery delivery = deliveryRepository.findByEventId(eventId)
                .orElseThrow(() -> new IllegalStateException("claimed but missing: " + eventId));
        Endpoint endpoint = endpointRepository.findById(delivery.getEndpointId())
                .orElseThrow(() -> new IllegalStateException("endpoint vanished: " + delivery.getEndpointId()));

        DeliveryAttempt attempt = delivery.startAttempt(now);
        deliveryRepository.save(delivery);

        log.debug("Started attempt event_id={} endpoint_id={} attempt={}",
                eventId, delivery.getEndpointId(), attempt.getAttemptNo());

        return Optional.of(new DispatchTask(
                delivery.getEventId(),
                delivery.getEndpointId(),
                endpoint.getUrl(),
                delivery.getEventType(),
                attempt.getAttemptNo(),
                delivery.getPayload()
        ));
    }

    @Transactional
    public void recordAttempt(String eventId, int attemptNo, HttpDeliveryResult result) {
        Instant now = Instant.now(clock);

        Delivery delivery = deliveryRepository.findByEventId(eventId)
                .orElseThrow(() -> new IllegalStateException("delivery missing: " + eventId));

        // Defensive: if the recovery service already marked this attempt as CRASH
        // and re-pended the delivery, a late-arriving HTTP completion can race in.
        // Honor the recovery's verdict — the receiver may or may not have seen the
        // first request, but the attempt history must reflect the crash, not the
        // late success. The same Delivery row gets a new attempt next dispatch.
        DeliveryAttempt attempt = delivery.getAttempts().stream()
                .filter(a -> a.getAttemptNo() == attemptNo)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "attempt missing: event_id=" + eventId + " attempt_no=" + attemptNo));
        if (attempt.getOutcome() != null) {
            log.info("Late HTTP result for already-recorded attempt — ignoring event_id={} attempt={}",
                    eventId, attemptNo);
            return;
        }
        if (delivery.getStatus() != DeliveryStatus.IN_FLIGHT) {
            log.info("Late HTTP result for delivery no longer IN_FLIGHT — ignoring event_id={} status={}",
                    eventId, delivery.getStatus());
            return;
        }

        attempt.record(result.outcome(), result.statusCode(), result.latencyMs(), result.error(), now);

        AttemptOutcome outcome = result.outcome();
        if (outcome == AttemptOutcome.SUCCESS) {
            delivery.succeed(now);
            deliveryRepository.save(delivery);
            log.info("Delivery succeeded event_id={} attempts={}", eventId, delivery.getAttemptCount());
            events.publishEvent(new DeliverySucceededEvent(eventId, delivery.getEndpointId(),
                    delivery.getAttemptCount(), now));
            return;
        }

        // Failure path. Decide retry vs dead-letter.
        events.publishEvent(new DeliveryFailedEvent(eventId, delivery.getEndpointId(), attemptNo,
                outcome, result.statusCode(), now));

        if (outcome == AttemptOutcome.PERMANENT_FAILURE) {
            String reason = "permanent_failure (status=" + result.statusCode() + ")";
            delivery.deadLetter(reason, now);
            deliveryRepository.save(delivery);
            log.warn("Dead-lettered (permanent) event_id={} reason={}", eventId, reason);
            events.publishEvent(new DeliveryDeadLetteredEvent(eventId, delivery.getEndpointId(),
                    delivery.getAttemptCount(), reason, now));
            return;
        }

        Optional<Duration> nextDelay = retryPolicy.nextDelay(attemptNo);
        if (nextDelay.isEmpty()) {
            String reason = "max_attempts_exceeded (" + retryPolicy.maxAttempts() + ")";
            delivery.deadLetter(reason, now);
            deliveryRepository.save(delivery);
            log.warn("Dead-lettered (exhausted) event_id={} reason={}", eventId, reason);
            events.publishEvent(new DeliveryDeadLetteredEvent(eventId, delivery.getEndpointId(),
                    delivery.getAttemptCount(), reason, now));
            return;
        }

        Instant next = now.plus(nextDelay.get());
        delivery.scheduleRetry(next);
        deliveryRepository.save(delivery);
        log.info("Scheduled retry event_id={} attempt={} next_attempt_at={} outcome={}",
                eventId, attemptNo, next, outcome);
    }
}
