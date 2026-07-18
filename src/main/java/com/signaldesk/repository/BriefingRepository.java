package com.signaldesk.repository;

import com.signaldesk.domain.Briefing;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

public interface BriefingRepository extends JpaRepository<Briefing, Long> {

    List<Briefing> findByBriefingDateOrderByCreatedAtDesc(LocalDate briefingDate);

    /** Remove an earlier same-day briefing for a ticker before regenerating. */
    @Transactional
    long deleteByTickerAndBriefingDate(String ticker, LocalDate briefingDate);

    /** Directional (BUY/SELL) briefings with an entry price that are old enough to score and not yet evaluated. */
    @Query("select b from Briefing b where b.evaluatedAt is null and b.entryPrice is not null "
            + "and b.briefingDate <= :cutoff "
            + "and b.signal in (com.signaldesk.domain.enums.Side.BUY, com.signaldesk.domain.enums.Side.SELL)")
    List<Briefing> findEvaluable(@Param("cutoff") LocalDate cutoff);

    List<Briefing> findByEvaluatedAtIsNotNullOrderByEvaluatedAtDesc();
}
