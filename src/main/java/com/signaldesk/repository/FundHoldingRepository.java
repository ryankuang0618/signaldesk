package com.signaldesk.repository;

import com.signaldesk.domain.FundHolding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface FundHoldingRepository extends JpaRepository<FundHolding, Long> {

    boolean existsByAccession(String accession);

    List<FundHolding> findByActorIdAndPeriodOfReport(Long actorId, LocalDate periodOfReport);

    /** Distinct filing periods for a fund, most recent first. */
    @Query("select distinct h.periodOfReport from FundHolding h "
            + "where h.actor.id = :actorId order by h.periodOfReport desc")
    List<LocalDate> findPeriodsDesc(@Param("actorId") Long actorId);
}
