package com.zenskar.billing.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.zenskar.billing.domain.Delivery;
import com.zenskar.billing.domain.DeliveryStatus;

public interface DeliveryRepository extends JpaRepository<Delivery, String> {

    Optional<Delivery> findByEventId(String eventId);

    /**
     * Same as {@link #findByEventId} but eagerly loads the attempt history so the
     * caller can inspect {@code getAttempts()} outside the original transaction.
     * Used by {@code QueryService} for endpoints that return a {@code DeliveryView}.
     */
    @EntityGraph(attributePaths = {"attempts"})
    @Query("select d from Delivery d where d.eventId = :eventId")
    Optional<Delivery> findByEventIdWithAttempts(@Param("eventId") String eventId);

    /**
     * Pending deliveries grouped by endpoint, used by the scheduler to round-robin
     * dispatches across endpoints so one slow endpoint cannot block others (S4).
     * Returns at most {@code limit} pending events for the given endpoint, oldest first.
     */
    @Query("""
            select d from Delivery d
            where d.endpointId = :endpointId
              and d.status = com.zenskar.billing.domain.DeliveryStatus.PENDING
              and d.nextAttemptAt <= :now
            order by d.nextAttemptAt asc, d.submittedAt asc
            """)
    List<Delivery> findDueByEndpoint(@Param("endpointId") String endpointId,
                                     @Param("now") Instant now,
                                     Limit limit);

    /**
     * Distinct endpoint ids that currently have any work due. Cheaper than scanning
     * the whole deliveries table and good enough for the round-robin scheduler.
     */
    @Query("""
            select distinct d.endpointId from Delivery d
            where d.status = com.zenskar.billing.domain.DeliveryStatus.PENDING
              and d.nextAttemptAt <= :now
            """)
    List<String> findEndpointsWithDueWork(@Param("now") Instant now);

    /**
     * Atomic claim: transition from PENDING to IN_FLIGHT only if still PENDING.
     * Returns the number of rows updated — 1 means we claimed it, 0 means someone else did.
     * This avoids load-then-save races between multiple scheduler instances/threads.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Delivery d
            set d.status = com.zenskar.billing.domain.DeliveryStatus.IN_FLIGHT,
                d.leaseHolder = :leaseHolder,
                d.leaseExpiresAt = :leaseExpiresAt
            where d.eventId = :eventId
              and d.status = com.zenskar.billing.domain.DeliveryStatus.PENDING
              and d.nextAttemptAt <= :now
            """)
    int claim(@Param("eventId") String eventId,
              @Param("leaseHolder") String leaseHolder,
              @Param("leaseExpiresAt") Instant leaseExpiresAt,
              @Param("now") Instant now);

    /**
     * Deliveries with an expired lease — the worker that held them likely crashed.
     * The {@code RecoveryService} re-pends these. {@link #findByEventId} is used
     * to apply the actual transition with proper attempt-history bookkeeping.
     */
    @Query("""
            select d from Delivery d
            where d.status = com.zenskar.billing.domain.DeliveryStatus.IN_FLIGHT
              and d.leaseExpiresAt < :now
            """)
    List<Delivery> findStaleLeases(@Param("now") Instant now);

    /** S7: queue depth for an endpoint = pending deliveries for that endpoint. */
    long countByEndpointIdAndStatus(String endpointId, DeliveryStatus status);

    /**
     * S9: dead-letter listing since a timestamp. The attempt history is fetch-joined
     * (via {@code @EntityGraph}) so the {@code DeliveryView} mapping in the controller
     * doesn't trigger a lazy load per row — one query instead of N+1. {@code distinct}
     * collapses the row duplication the collection join would otherwise produce.
     */
    @EntityGraph(attributePaths = {"attempts"})
    @Query("""
            select distinct d from Delivery d
            where d.status = com.zenskar.billing.domain.DeliveryStatus.DEAD_LETTERED
              and d.deadLetteredAt >= :since
            order by d.deadLetteredAt asc
            """)
    List<Delivery> findDeadLettersSince(@Param("since") Instant since);
}
