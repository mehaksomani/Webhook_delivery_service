package com.zenskar.billing.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;

import com.zenskar.billing.web.ApiExceptions.EndpointNotFoundException;
import com.zenskar.billing.domain.Endpoint;
import com.zenskar.billing.repository.EndpointRepository;
import com.zenskar.billing.security.UrlPolicy;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class EndpointService {

    private final EndpointRepository endpointRepository;
    private final UrlPolicy urlPolicy;
    private final Clock clock;

    @Transactional
    public Endpoint register(RegisterEndpointCommand cmd) {
        // SSRF guard: reject internal / metadata / non-http(s) targets up front so
        // a bad URL never enters the system. Re-checked at delivery time too.
        urlPolicy.validate(cmd.url());
        return endpointRepository.findById(cmd.endpointId())
                .map(existing -> {
                    existing.updateUrl(cmd.url());
                    log.debug("Updated existing endpoint URL: endpointId={} url={}", cmd.endpointId(), cmd.url());
                    return endpointRepository.save(existing);
                })
                .orElseGet(() -> {
                    Endpoint e = Endpoint.register(cmd.endpointId(), cmd.url(), Instant.now(clock));
                    log.info("Registered endpoint: endpointId={} url={}", cmd.endpointId(), cmd.url());
                    return endpointRepository.save(e);
                });
    }

    public Endpoint require(String endpointId) {
        return endpointRepository.findById(endpointId)
                .orElseThrow(() -> new EndpointNotFoundException("endpoint not found: " + endpointId));
    }
}
