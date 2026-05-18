package com.zenskar.billing.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.zenskar.billing.domain.Endpoint;

public interface EndpointRepository extends JpaRepository<Endpoint, String> {
}
