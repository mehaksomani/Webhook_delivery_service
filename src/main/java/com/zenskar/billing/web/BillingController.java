package com.zenskar.billing.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.zenskar.billing.domain.Delivery;
import com.zenskar.billing.domain.Endpoint;
import com.zenskar.billing.service.EndpointService;
import com.zenskar.billing.service.QueryService;
import com.zenskar.billing.service.RegisterEndpointCommand;
import com.zenskar.billing.service.SubmitEventCommand;
import com.zenskar.billing.service.SubmitService;
import com.zenskar.billing.web.ApiExceptions.EndpointNotFoundException;
import com.zenskar.billing.web.dto.DeliveryView;
import com.zenskar.billing.web.dto.RegisterEndpointRequest;
import com.zenskar.billing.web.dto.SubmitEventRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Inbound REST surface for the dispatcher. Merges what used to be three
 * controllers (Submit / Endpoint / Query) — they all serve the same
 * resource family and the per-controller files were ceremony.
 * <p>
 * Mapping to the spec's {@code submit/query} operations:
 *  <pre>
 *  POST   /api/v1/events                          submit(event)
 *  POST   /api/v1/endpoints                       (register, not in spec but tests need it)
 *  GET    /api/v1/queues/{endpointId}/depth       query("queue_depth", endpoint_id)
 *  GET    /api/v1/endpoints/{endpointId}/status   query("endpoint_status", endpoint_id)
 *  GET    /api/v1/dead-letters?since=…            query("dead_letters", since=…)
 *  GET    /api/v1/deliveries/{eventId}            runbook helper
 *  </pre>
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class BillingController {

    private final SubmitService submitService;
    private final EndpointService endpointService;
    private final QueryService queryService;

    // ---- write side ----

    /** Always returns 202 once the row is durable; processing happens in the background. */
    @PostMapping("/events")
    public ResponseEntity<Map<String, Object>> submit(@Valid @RequestBody SubmitEventRequest req) {
        Delivery d = submitService.submit(new SubmitEventCommand(
                req.eventId(),
                req.eventType(),
                req.endpointId(),
                req.payload() == null ? "" : req.payload()
        ));
        return ResponseEntity.accepted().body(Map.of(
                "event_id", d.getEventId(),
                "status", d.getStatus().name()
        ));
    }

    @PostMapping("/endpoints")
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterEndpointRequest req) {
        Endpoint e = endpointService.register(new RegisterEndpointCommand(req.endpointId(), req.url()));
        return ResponseEntity.ok(Map.of(
                "endpoint_id", e.getEndpointId(),
                "url", e.getUrl(),
                "health", e.getHealth().name(),
                "created_at", e.getCreatedAt()
        ));
    }

    // ---- read side ----

    @GetMapping("/queues/{endpointId}/depth")
    public ResponseEntity<Map<String, Object>> queueDepth(@PathVariable String endpointId) {
        long depth = queryService.queueDepth(endpointId);
        return ResponseEntity.ok(Map.of(
                "endpoint_id", endpointId,
                "queue_depth", depth
        ));
    }

    @GetMapping("/endpoints/{endpointId}/status")
    public ResponseEntity<Map<String, Object>> endpointStatus(@PathVariable String endpointId) {
        return ResponseEntity.ok(Map.of(
                "endpoint_id", endpointId,
                "status", queryService.endpointStatus(endpointId).name()
        ));
    }

    @GetMapping("/dead-letters")
    public ResponseEntity<List<DeliveryView>> deadLetters(
            @RequestParam("since")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        return ResponseEntity.ok(queryService.deadLettersSince(since).stream().map(DeliveryView::of).toList());
    }

    @GetMapping("/deliveries/{eventId}")
    public ResponseEntity<DeliveryView> getDelivery(@PathVariable String eventId) {
        return queryService.findDelivery(eventId)
                .map(DeliveryView::of)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new EndpointNotFoundException("delivery not found: " + eventId));
    }
}
