package com.signaldesk.repository;

import com.signaldesk.domain.ContextEvent;
import com.signaldesk.domain.enums.ContextType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ContextEventRepository extends JpaRepository<ContextEvent, Long> {

    List<ContextEvent> findByTickerOrderByEventAtDesc(String ticker);

    List<ContextEvent> findTop100ByOrderByEventAtDesc();

    boolean existsByTypeAndRef(ContextType type, String ref);
}
