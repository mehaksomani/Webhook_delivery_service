package com.zenskar.billing.pipeline;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
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
import com.zenskar.billing.observability.DeliveryEventLog;

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
    private final DeliveryEventLog eventLog;
    private final Clock clock;

    // AFTER_COMMIT so our SELECT sees the attempt that triggered this event;
    // @Async + REQUIRES_NEW keeps health bookkeeping off the dispatcher's hot path.
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

        // The query already excludes in-progress attempts (outcome IS NULL).
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

        EndpointHealth previous = endpoint.getHealth();
        EndpointHealth target = healthPolicy.evaluate(failures, consecutiveFailures);
        endpoint.applyHealth(target);
        try {
            endpointRepository.save(endpoint);
        } catch (OptimisticLockingFailureException e) {
            // A concurrent attempt for the same endpoint recomputed health first.
            // Harmless and self-healing: the next attempt re-evaluates from the same
            // window, so we drop this stale recompute instead of failing the listener.
            log.debug("Concurrent health update lost for endpoint_id={} — recomputed next attempt", endpointId);
            return;
        }

        if (previous != target) {
            eventLog.endpointHealthChanged(endpointId, previous.name(), target.name());
            log.warn("Endpoint health changed endpoint_id={} from={} to={}", endpointId, previous, target);
            events.publishEvent(new EndpointHealthChangedEvent(endpointId, previous, target, Instant.now(clock)));
        }
    }
}
