package com.zenskar.billing.web.dto;

import jakarta.validation.constraints.NotBlank;

public record RegisterEndpointRequest(
        @NotBlank String endpointId,
        @NotBlank String url
) {}
