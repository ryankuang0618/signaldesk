package com.signaldesk.repository;

import com.signaldesk.domain.CusipTicker;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CusipTickerRepository extends JpaRepository<CusipTicker, String> {
}
