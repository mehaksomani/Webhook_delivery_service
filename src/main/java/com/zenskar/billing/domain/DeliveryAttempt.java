package com.zenskar.billing.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One HTTP attempt against the endpoint. Owned by a Delivery; never created directly.
 * <p>
 * An attempt starts in {@code outcome == null}. The dispatcher updates it once the
 * HTTP call returns (or fails). Recorded attempts are immutable thereafter — historical.
 */
@Entity
@Table(
        name = "delivery_attempts",
        indexes = {
                @Index(name = "ix_attempts_by_endpoint_time",
                       columnList = "endpoint_id,started_at"),
                @Index(name = "ix_attempts_by_delivery",
                       columnList = "delivery_event_id")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeliveryAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delivery_event_id", nullable = false)
    private Delivery delivery;

    /**
     * Denormalized for fast querying ("recent attempts for endpoint X"). The Delivery
     * also has endpoint_id; we duplicate it so health-window queries don't need a join.
     */
    @Column(name = "endpoint_id", nullable = false, length = 64)
    private String endpointId;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 24)
    private AttemptOutcome outcome;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    static DeliveryAttempt started(Delivery delivery, int attemptNo, Instant startedAt) {
        DeliveryAttempt a = new DeliveryAttempt();
        a.delivery = delivery;
        a.endpointId = delivery.getEndpointId();
        a.attemptNo = attemptNo;
        a.startedAt = startedAt;
        return a;
    }

    public void record(AttemptOutcome outcome, Integer statusCode, Long latencyMs, String errorMessage, Instant finishedAt) {
        this.outcome = outcome;
        this.statusCode = statusCode;
        this.latencyMs = latencyMs;
        this.errorMessage = errorMessage;
        this.finishedAt = finishedAt;
    }
}
