package com.signaldesk.repository;

import com.signaldesk.domain.TradeSignal;
import com.signaldesk.domain.enums.Side;
import com.signaldesk.domain.enums.TradeSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TradeSignalRepository extends JpaRepository<TradeSignal, Long> {

    /** Distinct actors on one side for a ticker+source since a cutoff — used for insider-cluster detection. */
    @Query("select count(distinct s.actorName) from TradeSignal s "
            + "where s.ticker = :ticker and s.source = :source and s.side = :side and s.disclosedAt >= :since")
    long countDistinctActors(@Param("ticker") String ticker,
                             @Param("source") TradeSource source,
                             @Param("side") Side side,
                             @Param("since") Instant since);

    List<TradeSignal> findByTickerOrderByDisclosedAtDesc(String ticker);

    List<TradeSignal> findTop100ByOrderByDisclosedAtDesc();

    List<TradeSignal> findTop100BySourceOrderByDisclosedAtDesc(TradeSource source);

    /** Used by ingestion adapters to skip already-seen filings. */
    Optional<TradeSignal> findBySourceAndRawRef(TradeSource source, String rawRef);

    boolean existsBySourceAndRawRef(TradeSource source, String rawRef);
}
