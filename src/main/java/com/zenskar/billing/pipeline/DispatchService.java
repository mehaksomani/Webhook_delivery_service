package com.zenskar.billing.pipeline;

import java.time.Clock;
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
import com.zenskar.billing.domain.Endpoint;
import com.zenskar.billing.domain.RetryPolicy;
import com.zenskar.billing.repository.DeliveryRepository;
import com.zenskar.billing.repository.EndpointRepository;
import com.zenskar.billing.config.BillingProperties;
import com.zenskar.billing.http.HttpDeliveryResult;
import com.zenskar.billing.observability.DeliveryEventLog;

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
    private final DeliveryEventLog eventLog;
    private final Clock clock;

    /** Per-process identifier so a runbook can attribute a lease to a specific
     *  worker. host+PID is unique across instances on the same box (the old
     *  {@code user.name} form collided for co-located instances). */
    private final String leaseHolderId = buildLeaseHolderId();

    private static String buildLeaseHolderId() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown-host";
        }
        return "node-" + host + "-" + ProcessHandle.current().pid();
    }

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

        eventLog.dispatchStarted(eventId, delivery.getEndpointId(), attempt.getAttemptNo());
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

        // The aggregate owns the decision (success / retry / dead-letter) and absorbs
        // late or out-of-order async completions — e.g. if the recovery sweep already
        // marked this attempt as CRASH and re-pended the row, it returns IGNORED_* and
        // we honor that verdict rather than overwriting the crash with a late success.
        Delivery.Transition transition = delivery.recordAttemptResult(
                attemptNo, result.outcome(), result.statusCode(), result.latencyMs(), result.error(),
                retryPolicy, now);

        String endpointId = delivery.getEndpointId();
        switch (transition) {
            case IGNORED_DUPLICATE -> {
                log.info("Late HTTP result for already-recorded attempt — ignoring event_id={} attempt={}",
                        eventId, attemptNo);
                return;
            }
            case IGNORED_NOT_IN_FLIGHT -> {
                log.info("Late HTTP result for delivery no longer IN_FLIGHT — ignoring event_id={} status={}",
                        eventId, delivery.getStatus());
                return;
            }
            default -> { /* a real transition occurred — persist and notify below */ }
        }

        deliveryRepository.save(delivery);

        eventLog.httpRequestSent(eventId, endpointId, attemptNo, result.latencyMs());
        if (result.statusCode() != null) {
            eventLog.httpResponseReceived(eventId, endpointId, result.statusCode(), result.latencyMs());
        }

        AttemptOutcome outcome = result.outcome();
        switch (transition) {
            case SUCCEEDED -> {
                eventLog.deliverySucceeded(eventId, endpointId, delivery.getAttemptCount());
                log.info("Delivery succeeded event_id={} attempts={}", eventId, delivery.getAttemptCount());
                events.publishEvent(new DeliverySucceededEvent(eventId, endpointId,
                        delivery.getAttemptCount(), now));
            }
            case RETRY_SCHEDULED -> {
                events.publishEvent(new DeliveryFailedEvent(eventId, endpointId, attemptNo,
                        outcome, result.statusCode(), now));
                eventLog.retryScheduled(eventId, endpointId, attemptNo, outcome.name(), result.statusCode());
                log.info("Scheduled retry event_id={} attempt={} next_attempt_at={} outcome={}",
                        eventId, attemptNo, delivery.getNextAttemptAt(), outcome);
            }
            case DEAD_LETTERED -> {
                events.publishEvent(new DeliveryFailedEvent(eventId, endpointId, attemptNo,
                        outcome, result.statusCode(), now));
                String reason = delivery.getDeadLetterReason();
                eventLog.deliveryAbandoned(eventId, endpointId, attemptNo, reason, result.statusCode());
                log.warn("Dead-lettered event_id={} reason={}", eventId, reason);
                events.publishEvent(new DeliveryDeadLetteredEvent(eventId, endpointId,
                        delivery.getAttemptCount(), reason, now));
            }
            default -> { /* unreachable: IGNORED_* returned above */ }
        }
    }
}
