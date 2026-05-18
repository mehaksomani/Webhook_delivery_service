package com.zenskar.billing.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.zenskar.billing.web.ApiExceptions.EndpointNotFoundException;
import com.zenskar.billing.web.ApiExceptions.InvalidSubmissionException;
import com.zenskar.billing.domain.Delivery;
import com.zenskar.billing.repository.DeliveryRepository;
import com.zenskar.billing.repository.EndpointRepository;
import com.zenskar.billing.service.SubmitEventCommand;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubmitService {

    private final DeliveryRepository deliveryRepository;
    private final EndpointRepository endpointRepository;
    private final Clock clock;

    @Transactional
    public Delivery submit(SubmitEventCommand cmd) {
        validate(cmd);

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

    private void validate(SubmitEventCommand cmd) {
        if (cmd == null) throw new InvalidSubmissionException("command required");
        if (isBlank(cmd.eventId())) throw new InvalidSubmissionException("event_id required");
        if (isBlank(cmd.eventType())) throw new InvalidSubmissionException("event_type required");
        if (isBlank(cmd.endpointId())) throw new InvalidSubmissionException("endpoint_id required");
        if (cmd.payload() == null) throw new InvalidSubmissionException("payload required");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
