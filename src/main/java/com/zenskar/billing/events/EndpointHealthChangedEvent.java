package com.zenskar.billing.events;

import java.time.Instant;

import com.zenskar.billing.domain.EndpointHealth;

/** Fired when an endpoint's health transitions. Observability hook. */
public record EndpointHealthChangedEvent(
        String endpointId,
        EndpointHealth from,
        EndpointHealth to,
        Instant at
) {}
