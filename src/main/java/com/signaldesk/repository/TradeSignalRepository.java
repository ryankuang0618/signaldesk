package com.signaldesk.repository;

import com.signaldesk.domain.TradeSignal;
import com.signaldesk.domain.enums.TradeSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TradeSignalRepository extends JpaRepository<TradeSignal, Long> {

    List<TradeSignal> findByTickerOrderByDisclosedAtDesc(String ticker);

    List<TradeSignal> findTop100ByOrderByDisclosedAtDesc();

    List<TradeSignal> findTop100BySourceOrderByDisclosedAtDesc(TradeSource source);

    /** Used by ingestion adapters to skip already-seen filings. */
    Optional<TradeSignal> findBySourceAndRawRef(TradeSource source, String rawRef);

    boolean existsBySourceAndRawRef(TradeSource source, String rawRef);
}
