package com.zenskar.billing.pipeline;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.zenskar.billing.events.DeliveryFailedEvent;
import com.zenskar.billing.domain.AttemptOutcome;
import com.zenskar.billing.domain.Delivery;
import com.zenskar.billing.repository.DeliveryRepository;
import com.zenskar.billing.observability.DeliveryEventLog;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecoveryService {

    private static final String CRASH_NOTE = "worker_crashed_or_hung";

    private final DeliveryRepository deliveryRepository;
    private final ApplicationEventPublisher events;
    private final DeliveryEventLog eventLog;
    private final Clock clock;

    /**
     * Run once when the app is fully up. Critical for the S6 crash-recovery contract.
     * <p>
     * {@code @Transactional} here (in addition to {@link #recoverStaleLeases}) ensures
     * the transaction is actually started when Spring's proxy routes the
     * {@link ApplicationReadyEvent} to us — internal {@code this.recoverStaleLeases()}
     * calls bypass the proxy and would otherwise leave entities detached.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void onStartup() {
        int n = recoverStaleLeases();
        if (n > 0) {
            log.warn("Startup recovery re-pended {} stale leases", n);
        }
    }

    /** Safety net for workers that hung but didn't restart the process. */
    @Scheduled(fixedDelayString = "${billing.dispatcher.poll-interval-ms}")
    @Transactional
    public void periodicSweep() {
        try {
            recoverStaleLeases();
        } catch (Exception e) {
            log.error("Recovery sweep failed", e);
        }
    }

    @Transactional
    public int recoverStaleLeases() {
        Instant now = Instant.now(clock);
        List<Delivery> stale = deliveryRepository.findStaleLeases(now);
        if (stale.isEmpty()) return 0;

        int recovered = 0;
        for (Delivery d : stale) {
            // The aggregate marks its in-progress attempt as CRASH and re-pends itself,
            // so the attempt history stays honest without us touching its internals.
            int crashedAttemptNo = d.markCrashed(CRASH_NOTE, now);
            deliveryRepository.save(d);
            recovered++;
            eventLog.workerCrashed(d.getEventId(), d.getEndpointId(), crashedAttemptNo, CRASH_NOTE);
            log.warn("Recovered stale lease event_id={} attempts={} note=re-dispatched_after_lease_expiry",
                    d.getEventId(), d.getAttemptCount());

            // Crashes count toward endpoint health like any other failed attempt.
            // Published after the lease release so AFTER_COMMIT listeners see the
            // recovered state and the new attempt row together.
            events.publishEvent(new DeliveryFailedEvent(
                    d.getEventId(), d.getEndpointId(), crashedAttemptNo,
                    AttemptOutcome.CRASH, null, now));
        }
        return recovered;
    }
}
