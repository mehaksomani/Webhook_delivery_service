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

import org.springframework.transaction.annotation.Transactional;
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

    @Transactional(readOnly = true)
    public List<Delivery> deadLettersSince(Instant since) {
        // Attempts are eagerly fetched by the repository, so the returned entities
        // are safe to inspect after the transaction closes.
        return deliveryRepository.findDeadLettersSince(since);
    }

    @Transactional(readOnly = true)
    public Optional<Delivery> findDelivery(String eventId) {
        return deliveryRepository.findByEventIdWithAttempts(eventId);
    }
}
