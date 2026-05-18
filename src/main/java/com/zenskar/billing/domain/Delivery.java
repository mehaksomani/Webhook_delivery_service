package com.zenskar.billing.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * The aggregate root for delivering a single billing event to a single endpoint.
 * <p>
 * Identity is the caller-supplied {@code eventId} — this is what makes {@code submit()}
 * idempotent (S5) and what receivers dedupe on (S6). One row per event_id; no exceptions.
 * <p>
 * State transitions are exposed as named methods rather than setters so the lifecycle
 * (PENDING → IN_FLIGHT → SUCCEEDED | DEAD_LETTERED) is documented in code.
 */
@Entity
@Table(
        name = "deliveries",
        indexes = {
                @Index(name = "ix_deliveries_pending_by_endpoint",
                       columnList = "endpoint_id,status,next_attempt_at"),
                @Index(name = "ix_deliveries_lease",
                       columnList = "status,lease_expires_at"),
                @Index(name = "ix_deliveries_dlq",
                       columnList = "status,dead_lettered_at")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Delivery {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false, length = 128)
    private String eventId;

    @Column(name = "endpoint_id", nullable = false, length = 64)
    private String endpointId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Lob
    @Column(name = "payload", nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DeliveryStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "lease_holder", length = 64)
    private String leaseHolder;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "succeeded_at")
    private Instant succeededAt;

    @Column(name = "dead_lettered_at")
    private Instant deadLetteredAt;

    @Column(name = "dead_letter_reason", length = 256)
    private String deadLetterReason;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @OneToMany(mappedBy = "delivery", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("attemptNo ASC")
    private List<DeliveryAttempt> attempts = new ArrayList<>();

    private Delivery(String eventId, String endpointId, String eventType, String payload, Instant submittedAt) {
        this.eventId = eventId;
        this.endpointId = endpointId;
        this.eventType = eventType;
        this.payload = payload;
        this.submittedAt = submittedAt;
        this.nextAttemptAt = submittedAt;
        this.status = DeliveryStatus.PENDING;
        this.attemptCount = 0;
    }

    public static Delivery submit(String eventId, String endpointId, String eventType, String payload, Instant now) {
        if (eventId == null || eventId.isBlank()) throw new IllegalArgumentException("eventId required");
        if (endpointId == null || endpointId.isBlank()) throw new IllegalArgumentException("endpointId required");
        if (eventType == null || eventType.isBlank()) throw new IllegalArgumentException("eventType required");
        if (payload == null) throw new IllegalArgumentException("payload required");
        return new Delivery(eventId, endpointId, eventType, payload, now);
    }

    public List<DeliveryAttempt> getAttempts() {
        return Collections.unmodifiableList(attempts);
    }

    public void markInFlight(String leaseHolder, Instant leaseExpiresAt) {
        if (status != DeliveryStatus.PENDING) {
            throw new IllegalStateException("cannot mark in-flight from status=" + status);
        }
        this.status = DeliveryStatus.IN_FLIGHT;
        this.leaseHolder = leaseHolder;
        this.leaseExpiresAt = leaseExpiresAt;
    }

    public DeliveryAttempt startAttempt(Instant startedAt) {
        if (status != DeliveryStatus.IN_FLIGHT) {
            throw new IllegalStateException("cannot start attempt from status=" + status);
        }
        int attemptNo = this.attemptCount + 1;
        DeliveryAttempt attempt = DeliveryAttempt.started(this, attemptNo, startedAt);
        attempts.add(attempt);
        this.attemptCount = attemptNo;
        return attempt;
    }

    public void succeed(Instant succeededAt) {
        this.status = DeliveryStatus.SUCCEEDED;
        this.succeededAt = succeededAt;
        this.leaseHolder = null;
        this.leaseExpiresAt = null;
    }

    /** Stay in PENDING but schedule the next try. Used after a retriable failure. */
    public void scheduleRetry(Instant nextAttemptAt) {
        this.status = DeliveryStatus.PENDING;
        this.nextAttemptAt = nextAttemptAt;
        this.leaseHolder = null;
        this.leaseExpiresAt = null;
    }

    public void deadLetter(String reason, Instant when) {
        this.status = DeliveryStatus.DEAD_LETTERED;
        this.deadLetterReason = reason;
        this.deadLetteredAt = when;
        this.leaseHolder = null;
        this.leaseExpiresAt = null;
    }

    /** Used by the recovery service when a stale lease is reclaimed. */
    public void releaseLease(Instant nextAttemptAt) {
        if (status != DeliveryStatus.IN_FLIGHT) return;
        this.status = DeliveryStatus.PENDING;
        this.leaseHolder = null;
        this.leaseExpiresAt = null;
        this.nextAttemptAt = nextAttemptAt;
    }
}
