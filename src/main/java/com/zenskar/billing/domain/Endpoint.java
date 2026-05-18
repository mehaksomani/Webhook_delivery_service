package com.zenskar.billing.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A subscriber endpoint: a customer-configured URL that receives billing POSTs.
 * <p>
 * Health is a computed-and-cached value: the {@code EndpointHealthListener} updates it
 * after each attempt based on the recent window in {@code delivery_attempts}. We cache
 * here so the {@code query("endpoint_status", …)} call is O(1).
 * <p>
 * When health = TRIPPED, {@code trippedUntil} is set; the scheduler skips this endpoint
 * until then, then allows a single probe attempt.
 */
@Entity
@Table(name = "endpoints")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Endpoint {

    @Id
    @Column(name = "endpoint_id", length = 64, nullable = false, updatable = false)
    private String endpointId;

    @Column(name = "url", nullable = false, length = 1024)
    private String url;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "health", nullable = false, length = 16)
    private EndpointHealth health;

    @Column(name = "tripped_until")
    private Instant trippedUntil;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    private Endpoint(String endpointId, String url, Instant createdAt) {
        this.endpointId = endpointId;
        this.url = url;
        this.createdAt = createdAt;
        this.health = EndpointHealth.HEALTHY;
    }

    public static Endpoint register(String endpointId, String url, Instant now) {
        if (endpointId == null || endpointId.isBlank()) throw new IllegalArgumentException("endpointId required");
        if (url == null || url.isBlank()) throw new IllegalArgumentException("url required");
        return new Endpoint(endpointId, url, now);
    }

    public void updateUrl(String url) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("url required");
        this.url = url;
    }

    /** Used by {@code EndpointHealthListener} to apply the policy decision. */
    public void applyHealth(EndpointHealth target, Instant trippedUntil) {
        this.health = target;
        this.trippedUntil = target == EndpointHealth.TRIPPED ? trippedUntil : null;
    }

    public boolean isTripped(Instant now) {
        return health == EndpointHealth.TRIPPED && trippedUntil != null && now.isBefore(trippedUntil);
    }
}
