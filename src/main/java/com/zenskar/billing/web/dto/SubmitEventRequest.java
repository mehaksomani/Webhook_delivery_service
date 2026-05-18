package com.zenskar.billing.web.dto;

import jakarta.validation.constraints.NotBlank;

public record SubmitEventRequest(
        @NotBlank(message = "event_id required") String eventId,
        @NotBlank(message = "event_type required") String eventType,
        @NotBlank(message = "endpoint_id required") String endpointId,
        String payload
) {}
