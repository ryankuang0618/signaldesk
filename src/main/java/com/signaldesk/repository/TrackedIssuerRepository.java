package com.signaldesk.repository;

import com.signaldesk.domain.TrackedIssuer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TrackedIssuerRepository extends JpaRepository<TrackedIssuer, Long> {

    List<TrackedIssuer> findByActiveTrue();
}
