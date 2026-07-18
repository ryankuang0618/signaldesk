package com.signaldesk.repository;

import com.signaldesk.domain.BriefingScore;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface BriefingScoreRepository extends JpaRepository<BriefingScore, Long> {

    /** Pending calls (not yet evaluated) whose horizon window has passed. */
    List<BriefingScore> findByEvaluatedAtIsNullAndDueDateLessThanEqual(LocalDate date);

    /** All evaluated calls, newest first — for stats and the results endpoint. */
    List<BriefingScore> findByEvaluatedAtIsNotNullOrderByEvaluatedAtDesc();
}
