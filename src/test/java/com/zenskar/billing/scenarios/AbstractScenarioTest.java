package com.zenskar.billing.scenarios;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.zenskar.billing.repository.DeliveryAttemptRepository;
import com.zenskar.billing.repository.DeliveryRepository;
import com.zenskar.billing.repository.EndpointRepository;
import com.zenskar.billing.service.EndpointService;
import com.zenskar.billing.service.QueryService;
import com.zenskar.billing.service.SubmitService;
import com.zenskar.billing.service.RegisterEndpointCommand;
import com.zenskar.billing.service.SubmitEventCommand;

/**
 * Shared setup for the S1-S9 scenario tests.
 * <p>
 * One Spring context is reused across all scenario classes (matching annotations);
 * a fresh WireMock instance is started for each test class. Database state is
 * cleared between individual tests via the JPA repositories so the scheduler
 * never picks up rows from a previous test.
 */
@SpringBootTest
public abstract class AbstractScenarioTest {

    protected static WireMockServer wireMock;

    @Autowired protected SubmitService submitService;
    @Autowired protected EndpointService endpointService;
    @Autowired protected QueryService queryService;
    @Autowired protected DeliveryRepository deliveryRepository;
    @Autowired protected DeliveryAttemptRepository attemptRepository;
    @Autowired protected EndpointRepository endpointRepository;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @BeforeEach
    void cleanState() {
        wireMock.resetAll();
        attemptRepository.deleteAllInBatch();
        deliveryRepository.deleteAllInBatch();
        endpointRepository.deleteAllInBatch();
    }

    @AfterEach
    void noLingeringWork() {
        // Defensive — make sure no in-flight rows leak into the next test.
        attemptRepository.deleteAllInBatch();
        deliveryRepository.deleteAllInBatch();
        endpointRepository.deleteAllInBatch();
    }

    // ---- Helpers ----

    protected void registerEndpoint(String endpointId, String path) {
        endpointService.register(new RegisterEndpointCommand(endpointId, wireMock.baseUrl() + path));
    }

    protected void submit(String eventId, String endpointId) {
        submitService.submit(new SubmitEventCommand(eventId, "test.event", endpointId, "{\"hello\":\"world\"}"));
    }
}
