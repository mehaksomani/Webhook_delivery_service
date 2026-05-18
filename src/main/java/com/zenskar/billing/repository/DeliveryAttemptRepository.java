package com.zenskar.billing.repository;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.zenskar.billing.domain.DeliveryAttempt;

public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, Long> {

    /**
     * The last N completed attempts for an endpoint, newest first. Used by
     * {@code EndpointHealthListener} to compute the sliding-window failure count
     * after each attempt completes.
     */
    @Query("""
            select a from DeliveryAttempt a
            where a.endpointId = :endpointId
              and a.outcome is not null
            order by a.startedAt desc
            """)
    List<DeliveryAttempt> findRecentByEndpoint(@Param("endpointId") String endpointId, Limit limit);
}
