package com.zenskar.billing.pipeline;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.zenskar.billing.events.DeliveryFailedEvent;
import com.zenskar.billing.events.DeliverySucceededEvent;
import com.zenskar.billing.events.EndpointHealthChangedEvent;
import com.zenskar.billing.domain.DeliveryAttempt;
import com.zenskar.billing.domain.Endpoint;
import com.zenskar.billing.domain.EndpointHealth;
import com.zenskar.billing.domain.HealthPolicy;
import com.zenskar.billing.repository.DeliveryAttemptRepository;
import com.zenskar.billing.repository.EndpointRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Recomputes endpoint health after each delivery attempt and broadcasts
 * {@link EndpointHealthChangedEvent} when state transitions.
 * <p>
 * Runs in its own transaction (asynchronously) so health bookkeeping never
 * blocks or rolls back the originating attempt recording.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EndpointHealthListener {

    private final DeliveryAttemptRepository attemptRepository;
    private final EndpointRepository endpointRepository;
    private final HealthPolicy healthPolicy;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    // @TransactionalEventListener with AFTER_COMMIT defers invocation until the
    // publishing transaction (the one that recorded the attempt outcome) has
    // committed — otherwise our SELECT for the recent attempts could miss the
    // attempt that just triggered this event. The @Async runs the listener on
    // a separate thread/transaction so health bookkeeping never holds up the
    // dispatcher's hot path.
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSucceeded(DeliverySucceededEvent event) {
        evaluate(event.endpointId());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onFailed(DeliveryFailedEvent event) {
        evaluate(event.endpointId());
    }

    private void evaluate(String endpointId) {
        Optional<Endpoint> maybe = endpointRepository.findById(endpointId);
        if (maybe.isEmpty()) return;
        Endpoint endpoint = maybe.get();

        // Most recent first. The repository query already filters out attempts
        // still in progress (outcome IS NULL), so each row here has a final outcome.
        List<DeliveryAttempt> recent = attemptRepository.findRecentByEndpoint(endpointId,
                Limit.of(healthPolicy.windowSize()));
        int failures = 0;
        int consecutiveFailures = 0;
        boolean stillCounting = true;
        for (DeliveryAttempt a : recent) {
            if (a.getOutcome().isFailure()) {
                failures++;
                if (stillCounting) consecutiveFailures++;
            } else {
                stillCounting = false;
            }
        }

        HealthPolicy.Decision decision = healthPolicy.evaluate(failures, consecutiveFailures);
        EndpointHealth previous = endpoint.getHealth();

        Instant trippedUntil = decision.target() == EndpointHealth.TRIPPED
                ? Instant.now(clock).plus(decision.cooldown())
                : null;
        endpoint.applyHealth(decision.target(), trippedUntil);
        endpointRepository.save(endpoint);

        if (previous != endpoint.getHealth()) {
            log.warn("Endpoint health changed endpoint_id={} from={} to={} cooldown={}",
                    endpointId, previous, endpoint.getHealth(),
                    decision.target() == EndpointHealth.TRIPPED ? decision.cooldown() : "n/a");
            events.publishEvent(new EndpointHealthChangedEvent(endpointId, previous,
                    endpoint.getHealth(), Instant.now(clock)));
        }
    }
}
