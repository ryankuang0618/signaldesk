package com.signaldesk.web.rest;

import com.signaldesk.domain.NewsItem;
import com.signaldesk.ingestion.news.NewsIngestionService;
import com.signaldesk.repository.NewsItemRepository;
import com.signaldesk.web.ws.LiveUpdatePublisher;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Read company news, and trigger a news ingestion run. */
@RestController
@RequestMapping("/api/news")
public class NewsController {

    private final NewsItemRepository news;
    private final NewsIngestionService ingestion;
    private final LiveUpdatePublisher live;

    public NewsController(NewsItemRepository news, NewsIngestionService ingestion, LiveUpdatePublisher live) {
        this.news = news;
        this.ingestion = ingestion;
        this.live = live;
    }

    @GetMapping
    public List<NewsItem> list(@RequestParam(required = false) String ticker) {
        if (ticker != null && !ticker.isBlank()) {
            return news.findByTickerOrderByPublishedAtDesc(ticker.toUpperCase());
        }
        return news.findTop100ByOrderByPublishedAtDesc();
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh() {
        int stored = ingestion.ingestAll();
        live.publish("NEWS", stored);
        return Map.of("newArticles", stored, "total", news.count());
    }
}
