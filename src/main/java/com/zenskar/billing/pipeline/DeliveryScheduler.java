package com.zenskar.billing.pipeline;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.zenskar.billing.domain.Delivery;
import com.zenskar.billing.repository.DeliveryRepository;
import com.zenskar.billing.config.BillingProperties;
import com.zenskar.billing.http.BillingHttpClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Polls for due deliveries and fires HTTP attempts. The key property we preserve:
 * one endpoint's slow HTTP cannot delay another endpoint's dispatch (S4).
 * <p>
 * Mechanism:
 * <ol>
 *   <li>List endpoints that have at least one pending delivery due now.</li>
 *   <li>For each endpoint, only pull deliveries up to its per-endpoint in-flight
 *       cap. A slow endpoint quickly fills its cap and is skipped on subsequent
 *       ticks until its in-flight count drops.</li>
 *   <li>Each dispatch is asynchronous: the scheduler thread fires the HTTP
 *       request and moves on. Slow responses sit in the HTTP client's I/O loop;
 *       they never block the scheduler from servicing the next endpoint.</li>
 * </ol>
 * This is why the spec note "must pass even if your service is single-threaded"
 * works: concurrency comes from non-blocking I/O, not from threads.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeliveryScheduler {

    private final DeliveryRepository deliveryRepository;
    private final DispatchService dispatchService;
    private final BillingHttpClient httpClient;
    private final BillingProperties props;
    private final Clock clock;

    private final Map<String, AtomicInteger> inFlightByEndpoint = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${billing.dispatcher.poll-interval-ms}")
    public void tick() {
        try {
            doTick();
        } catch (Exception e) {
            log.error("Scheduler tick failed", e);
        }
    }

    private void doTick() {
        Instant now = Instant.now(clock);
        List<String> endpoints = deliveryRepository.findEndpointsWithDueWork(now);
        if (endpoints.isEmpty()) return;

        for (String endpointId : endpoints) {
            try {
                dispatchEndpoint(endpointId, now);
            } catch (Exception e) {
                log.error("Per-endpoint dispatch failed for {}", endpointId, e);
            }
        }
    }

    private void dispatchEndpoint(String endpointId, Instant now) {
        AtomicInteger counter = inFlightByEndpoint.computeIfAbsent(endpointId, k -> new AtomicInteger());
        int slots = props.dispatcher().maxInFlightPerEndpoint() - counter.get();
        if (slots <= 0) return;

        int batch = Math.min(slots, props.dispatcher().batchSize());
        List<Delivery> candidates = deliveryRepository.findDueByEndpoint(endpointId, now, Limit.of(batch));
        for (Delivery candidate : candidates) {
            if (counter.get() >= props.dispatcher().maxInFlightPerEndpoint()) break;

            Optional<DispatchTask> maybe = dispatchService.startAttempt(candidate.getEventId());
            if (maybe.isEmpty()) continue; // race with another worker, or no longer PENDING

            DispatchTask task = maybe.get();
            counter.incrementAndGet();

            // The increment above is balanced by the decrement in whenComplete. If
            // deliver(...) throws synchronously (before the future is wired up), the
            // catch here releases the slot — otherwise a single failure would leak a
            // permanent in-flight slot and eventually starve the endpoint. The row
            // stays IN_FLIGHT and the lease sweep re-pends it.
            try {
                httpClient.deliver(task.url(), task.eventId(), task.eventType(), task.attemptNo(), task.payload())
                        .whenComplete((result, throwable) -> {
                            try {
                                if (result != null) {
                                    dispatchService.recordAttempt(task.eventId(), task.attemptNo(), result);
                                } else if (throwable != null) {
                                    log.error("HTTP future failed for event_id={}", task.eventId(), throwable);
                                }
                            } catch (RuntimeException e) {
                                // e.g. an optimistic-lock clash recording the attempt. Don't let it
                                // vanish into the unobserved future; the lease sweep is the backstop.
                                log.error("Failed to record attempt event_id={} attempt={}",
                                        task.eventId(), task.attemptNo(), e);
                            } finally {
                                counter.decrementAndGet();
                            }
                        });
            } catch (RuntimeException e) {
                counter.decrementAndGet();
                log.error("Synchronous dispatch failure event_id={}", task.eventId(), e);
            }
        }
    }
}
