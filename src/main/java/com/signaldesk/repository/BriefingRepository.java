package com.signaldesk.repository;

import com.signaldesk.domain.Briefing;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

public interface BriefingRepository extends JpaRepository<Briefing, Long> {

    List<Briefing> findByBriefingDateOrderByCreatedAtDesc(LocalDate briefingDate);

    /** Remove an earlier same-day briefing for a ticker before regenerating (cascades to its scores). */
    @Transactional
    long deleteByTickerAndBriefingDate(String ticker, LocalDate briefingDate);
}
