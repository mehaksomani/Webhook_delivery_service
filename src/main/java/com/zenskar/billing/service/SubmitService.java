package com.zenskar.billing.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.zenskar.billing.web.ApiExceptions.EndpointNotFoundException;
import com.zenskar.billing.domain.Delivery;
import com.zenskar.billing.repository.DeliveryRepository;
import com.zenskar.billing.repository.EndpointRepository;
import com.zenskar.billing.observability.DeliveryEventLog;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubmitService {

    private final DeliveryRepository deliveryRepository;
    private final EndpointRepository endpointRepository;
    private final DeliveryEventLog eventLog;
    private final Clock clock;

    @Transactional
    public Delivery submit(SubmitEventCommand cmd) {
        // Inbound validation is enforced two ways already: @Valid on the request DTO
        // (user-facing 400s) and the Delivery.submit factory (domain invariants). No
        // third copy here.

        // Idempotent: existing event_id -> no-op, return existing record.
        var existing = deliveryRepository.findByEventId(cmd.eventId());
        if (existing.isPresent()) {
            log.info("Submit is a no-op (event_id already known): event_id={}", cmd.eventId());
            return existing.get();
        }

        if (!endpointRepository.existsById(cmd.endpointId())) {
            throw new EndpointNotFoundException("endpoint not found: " + cmd.endpointId());
        }

        Instant now = Instant.now(clock);
        Delivery d = Delivery.submit(cmd.eventId(), cmd.endpointId(), cmd.eventType(), cmd.payload(), now);

        try {
            deliveryRepository.save(d);
            eventLog.eventSubmitted(cmd.eventId(), cmd.eventType(), cmd.endpointId());
            log.info("Submitted event: event_id={} endpoint_id={} event_type={}",
                    cmd.eventId(), cmd.endpointId(), cmd.eventType());
            return d;
        } catch (DataIntegrityViolationException race) {
            // Concurrent submit with the same event_id won the race; return the survivor.
            log.info("Concurrent duplicate submit detected (idempotent): event_id={}", cmd.eventId());
            return deliveryRepository.findByEventId(cmd.eventId())
                    .orElseThrow(() -> new IllegalStateException("race resolution failed: " + cmd.eventId()));
        }
    }
}
