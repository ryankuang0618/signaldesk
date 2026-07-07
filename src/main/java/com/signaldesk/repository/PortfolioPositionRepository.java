package com.signaldesk.repository;

import com.signaldesk.domain.PortfolioPosition;
import com.signaldesk.domain.enums.PositionSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PortfolioPositionRepository extends JpaRepository<PortfolioPosition, Long> {

    List<PortfolioPosition> findBySource(PositionSource source);

    Optional<PortfolioPosition> findBySourceAndTicker(PositionSource source, String ticker);
}
