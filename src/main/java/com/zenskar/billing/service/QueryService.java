package com.zenskar.billing.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.zenskar.billing.web.ApiExceptions.EndpointNotFoundException;
import com.zenskar.billing.domain.Delivery;
import com.zenskar.billing.domain.DeliveryStatus;
import com.zenskar.billing.domain.EndpointHealth;
import com.zenskar.billing.repository.DeliveryRepository;
import com.zenskar.billing.repository.EndpointRepository;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class QueryService {

    private final DeliveryRepository deliveryRepository;
    private final EndpointRepository endpointRepository;

    public long queueDepth(String endpointId) {
        return deliveryRepository.countByEndpointIdAndStatus(endpointId, DeliveryStatus.PENDING);
    }

    public EndpointHealth endpointStatus(String endpointId) {
        return endpointRepository.findById(endpointId)
                .map(e -> e.getHealth())
                .orElseThrow(() -> new EndpointNotFoundException("endpoint not found: " + endpointId));
    }

    @Transactional
    public List<Delivery> deadLettersSince(Instant since) {
        // Touch attempts so the returned entities can be inspected outside this TX.
        List<Delivery> dls = deliveryRepository.findDeadLettersSince(since);
        dls.forEach(d -> d.getAttempts().size());
        return dls;
    }

    @Transactional
    public Optional<Delivery> findDelivery(String eventId) {
        return deliveryRepository.findByEventIdWithAttempts(eventId);
    }
}
