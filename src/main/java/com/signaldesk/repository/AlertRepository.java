package com.signaldesk.repository;

import com.signaldesk.domain.Alert;
import com.signaldesk.domain.enums.Side;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    /** Alerts generated but not yet pushed to LINE. */
    List<Alert> findBySentAtIsNull();

    List<Alert> findTop50ByOrderByCreatedAtDesc();

    boolean existsByTickerAndSignalAndCreatedAtAfter(String ticker, Side signal, Instant after);
}
