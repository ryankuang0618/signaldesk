package com.signaldesk.ingestion.news;

import com.signaldesk.domain.NewsItem;
import com.signaldesk.domain.PortfolioPosition;
import com.signaldesk.domain.TradeSignal;
import com.signaldesk.domain.TrackedIssuer;
import com.signaldesk.domain.enums.PositionSource;
import com.signaldesk.repository.NewsItemRepository;
import com.signaldesk.repository.PortfolioPositionRepository;
import com.signaldesk.repository.TradeSignalRepository;
import com.signaldesk.repository.TrackedIssuerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Fetches recent company news for the tickers we care about (tracked issuers + tickers appearing
 * in recent signals) and stores them, deduplicated by URL. Disabled gracefully without an API key.
 */
@Service
public class NewsIngestionService {

    private static final Logger log = LoggerFactory.getLogger(NewsIngestionService.class);

    private final FinnhubNewsClient client;
    private final NewsItemRepository news;
    private final TrackedIssuerRepository issuers;
    private final TradeSignalRepository signals;
    private final PortfolioPositionRepository positions;

    private final boolean enabled;
    private final int lookbackDays;
    private final int maxTickers;
    private final long throttleMs;

    public NewsIngestionService(FinnhubNewsClient client,
                                NewsItemRepository news,
                                TrackedIssuerRepository issuers,
                                TradeSignalRepository signals,
                                PortfolioPositionRepository positions,
                                @Value("${app.news.enabled:true}") boolean enabled,
                                @Value("${app.news.lookback-days:7}") int lookbackDays,
                                @Value("${app.news.max-tickers:20}") int maxTickers,
                                @Value("${app.news.throttle-ms:1100}") long throttleMs) {
        this.client = client;
        this.news = news;
        this.issuers = issuers;
        this.signals = signals;
        this.positions = positions;
        this.enabled = enabled;
        this.lookbackDays = lookbackDays;
        this.maxTickers = maxTickers;
        this.throttleMs = throttleMs;
    }

    /** Ingest news for the target tickers. Returns the number of new articles stored. */
    public int ingestAll() {
        if (!enabled) {
            log.info("News ingestion disabled (app.news.enabled=false)");
            return 0;
        }
        if (!client.hasApiKey()) {
            log.info("News ingestion skipped — no Finnhub API key set (FINNHUB_API_KEY / app.news.api-key)");
            return 0;
        }

        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(lookbackDays);
        int total = 0;
        for (String ticker : targetTickers()) {
            try {
                total += ingestTicker(ticker, from, to);
            } catch (Exception e) {
                log.warn("News ingestion failed for {}: {}", ticker, e.getMessage());
            }
            throttle();
        }
        log.info("News ingestion complete — {} new article(s)", total);
        return total;
    }

    private int ingestTicker(String ticker, LocalDate from, LocalDate to) {
        int stored = 0;
        for (NewsDto a : client.companyNews(ticker, from, to)) {
            if (a.url() == null || a.url().isBlank() || news.existsByUrl(a.url())) {
                continue;
            }
            NewsItem n = new NewsItem();
            n.setTicker(ticker);
            n.setHeadline(a.headline() != null ? a.headline() : "(no headline)");
            n.setUrl(a.url());
            n.setSource(a.source());
            if (a.datetime() > 0) {
                n.setPublishedAt(Instant.ofEpochSecond(a.datetime()));
            }
            news.save(n);
            stored++;
        }
        return stored;
    }

    /** Tracked issuers + your holdings (both guaranteed), then recent-signal tickers up to the cap. */
    private Set<String> targetTickers() {
        Set<String> tickers = new LinkedHashSet<>();
        for (TrackedIssuer i : issuers.findByActiveTrue()) {
            tickers.add(i.getTicker());
        }
        for (PortfolioPosition p : positions.findBySource(PositionSource.MANUAL)) {
            if (p.getTicker() != null) {
                tickers.add(p.getTicker().toUpperCase(Locale.ROOT));
            }
        }
        for (TradeSignal s : signals.findTop100ByOrderByDisclosedAtDesc()) {
            if (tickers.size() >= maxTickers) {
                break;
            }
            if (s.getTicker() != null) {
                tickers.add(s.getTicker());
            }
        }
        return tickers;
    }

    private void throttle() {
        try {
            TimeUnit.MILLISECONDS.sleep(throttleMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
