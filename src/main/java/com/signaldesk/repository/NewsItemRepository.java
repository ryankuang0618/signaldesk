package com.signaldesk.repository;

import com.signaldesk.domain.NewsItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NewsItemRepository extends JpaRepository<NewsItem, Long> {

    List<NewsItem> findByTickerOrderByPublishedAtDesc(String ticker);

    List<NewsItem> findTop100ByOrderByPublishedAtDesc();

    boolean existsByUrl(String url);
}
