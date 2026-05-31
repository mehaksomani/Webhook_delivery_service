package com.zenskar.billing.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

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
 * The aggregate root for delivering a single billing event to a single
 * endpoint.
 * <p>
 * Identity is the caller-supplied {@code eventId} — this is what makes
 * {@code submit()}
 * idempotent (S5) and what receivers dedupe on (S6). One row per event_id; no
 * exceptions.
 * <p>
 * State transitions are exposed as named methods rather than setters so the
 * lifecycle
 * (PENDING → IN_FLIGHT → SUCCEEDED | DEAD_LETTERED) is documented in code.
 */
@Entity
@Table(name = "deliveries", indexes = {
        @Index(name = "ix_deliveries_pending_by_endpoint", columnList = "endpoint_id,status,next_attempt_at"),
        @Index(name = "ix_deliveries_lease", columnList = "status,lease_expires_at"),
        @Index(name = "ix_deliveries_dlq", columnList = "status,dead_lettered_at")
})
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
        if (eventId == null || eventId.isBlank())
            throw new IllegalArgumentException("eventId required");
        if (endpointId == null || endpointId.isBlank())
            throw new IllegalArgumentException("endpointId required");
        if (eventType == null || eventType.isBlank())
            throw new IllegalArgumentException("eventType required");
        if (payload == null)
            throw new IllegalArgumentException("payload required");
        return new Delivery(eventId, endpointId, eventType, payload, now);
    }

    public List<DeliveryAttempt> getAttempts() {
        return Collections.unmodifiableList(attempts);
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

    /**
     * What happened to the delivery's lifecycle when an attempt result was applied.
     * Returned by {@link #recordAttemptResult} so the caller can emit the matching
     * events/logs without re-deriving the decision. {@code IGNORED_*} values mean a
     * late or out-of-order async result was discarded and no state changed.
     */
    public enum Transition {
        SUCCEEDED,
        RETRY_SCHEDULED,
        DEAD_LETTERED,
        IGNORED_DUPLICATE,
        IGNORED_NOT_IN_FLIGHT
    }

    /**
     * Records the outcome of attempt {@code attemptNo} and advances the lifecycle:
     * success terminates, a permanent failure or exhausted retry budget dead-letters,
     * and a retriable failure schedules the next attempt per {@code retryPolicy}.
     * <p>
     * Late async completions are absorbed here rather than in the caller: if the
     * attempt was already recorded, or the delivery is no longer IN_FLIGHT (the
     * recovery sweep re-pended it), nothing changes and an {@code IGNORED_*}
     * transition is returned.
     */
    public Transition recordAttemptResult(int attemptNo, AttemptOutcome outcome, Integer statusCode,
            Long latencyMs, String error, RetryPolicy retryPolicy, Instant now) {
        DeliveryAttempt attempt = attempts.stream()
                .filter(a -> a.getAttemptNo() == attemptNo)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "attempt missing: event_id=" + eventId + " attempt_no=" + attemptNo));

        if (attempt.getOutcome() != null) return Transition.IGNORED_DUPLICATE;
        if (status != DeliveryStatus.IN_FLIGHT) return Transition.IGNORED_NOT_IN_FLIGHT;

        attempt.record(outcome, statusCode, latencyMs, error, now);

        if (outcome == AttemptOutcome.SUCCESS) {
            succeed(now);
            return Transition.SUCCEEDED;
        }
        if (outcome == AttemptOutcome.PERMANENT_FAILURE) {
            String detail = statusCode != null ? "status=" + statusCode : error;
            deadLetter("permanent_failure (" + detail + ")", now);
            return Transition.DEAD_LETTERED;
        }
        Optional<Duration> nextDelay = retryPolicy.nextDelay(attemptNo);
        if (nextDelay.isEmpty()) {
            deadLetter("max_attempts_exceeded (" + retryPolicy.maxAttempts() + ")", now);
            return Transition.DEAD_LETTERED;
        }
        scheduleRetry(now.plus(nextDelay.get()));
        return Transition.RETRY_SCHEDULED;
    }

    /**
     * Used by the recovery service when a stale lease is reclaimed. Marks the
     * in-progress attempt (if any) as a CRASH so the attempt history is honest,
     * re-pends the delivery, and returns the crashed attempt number for logging.
     */
    public int markCrashed(String note, Instant now) {
        DeliveryAttempt inFlight = attempts.stream()
                .filter(a -> a.getOutcome() == null)
                .reduce((first, second) -> second)
                .orElse(null);
        int crashedAttemptNo = inFlight != null ? inFlight.getAttemptNo() : attemptCount;
        if (inFlight != null) {
            inFlight.record(AttemptOutcome.CRASH, null, 0L, note, now);
        }
        releaseLease(now);
        return crashedAttemptNo;
    }

    // ---- internal lifecycle transitions (driven by the methods above) ----

    private void succeed(Instant succeededAt) {
        this.status = DeliveryStatus.SUCCEEDED;
        this.succeededAt = succeededAt;
        this.leaseHolder = null;
        this.leaseExpiresAt = null;
    }

    /** Stay in PENDING but schedule the next try. Used after a retriable failure. */
    private void scheduleRetry(Instant nextAttemptAt) {
        this.status = DeliveryStatus.PENDING;
        this.nextAttemptAt = nextAttemptAt;
        this.leaseHolder = null;
        this.leaseExpiresAt = null;
    }

    private void deadLetter(String reason, Instant when) {
        this.status = DeliveryStatus.DEAD_LETTERED;
        this.deadLetterReason = reason;
        this.deadLetteredAt = when;
        this.leaseHolder = null;
        this.leaseExpiresAt = null;
    }

    private void releaseLease(Instant nextAttemptAt) {
        if (status != DeliveryStatus.IN_FLIGHT)
            return;
        this.status = DeliveryStatus.PENDING;
        this.leaseHolder = null;
        this.leaseExpiresAt = null;
        this.nextAttemptAt = nextAttemptAt;
    }
}
