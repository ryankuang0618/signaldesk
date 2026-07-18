package com.signaldesk.ingestion.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.signaldesk.domain.ContextEvent;
import com.signaldesk.domain.PortfolioPosition;
import com.signaldesk.domain.TradeSignal;
import com.signaldesk.domain.TrackedIssuer;
import com.signaldesk.domain.enums.ContextType;
import com.signaldesk.domain.enums.PositionSource;
import com.signaldesk.repository.ContextEventRepository;
import com.signaldesk.repository.PortfolioPositionRepository;
import com.signaldesk.repository.TradeSignalRepository;
import com.signaldesk.repository.TrackedIssuerRepository;
import com.signaldesk.web.ws.LiveUpdatePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Populates {@link ContextType#MARKET} context from Alpaca daily bars: momentum (trailing returns),
 * relative strength vs SPY, average dollar volume (liquidity), and realized volatility. This is the
 * quantitative price context Finnhub's free tier can't give — it lets the AI tell "good news after a
 * 15% run" apart from "good news with no reaction", and flags illiquid or high-volatility names.
 */
@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);
    private static final String BENCHMARK = "SPY";

    private final AlpacaMarketClient alpaca;
    private final ContextEventRepository context;
    private final TrackedIssuerRepository issuers;
    private final TradeSignalRepository signals;
    private final PortfolioPositionRepository positions;
    private final LiveUpdatePublisher live;

    private final boolean enabled;
    private final int maxTickers;
    private final int lookbackDays;
    private final boolean consolidatedVolume;

    public MarketDataService(AlpacaMarketClient alpaca,
                             ContextEventRepository context,
                             TrackedIssuerRepository issuers,
                             TradeSignalRepository signals,
                             PortfolioPositionRepository positions,
                             LiveUpdatePublisher live,
                             @Value("${app.alpaca.enabled:true}") boolean enabled,
                             @Value("${app.alpaca.max-tickers:12}") int maxTickers,
                             @Value("${app.alpaca.lookback-days:120}") int lookbackDays,
                             @Value("${app.alpaca.feed:iex}") String feed) {
        this.alpaca = alpaca;
        this.context = context;
        this.issuers = issuers;
        this.signals = signals;
        this.positions = positions;
        this.live = live;
        this.enabled = enabled;
        this.maxTickers = maxTickers;
        this.lookbackDays = lookbackDays;
        // The free IEX feed reports only IEX-executed volume (~2-3% of consolidated); label it so the
        // dollar-volume figure isn't misread as total market liquidity. 'sip' (paid) is consolidated.
        this.consolidatedVolume = "sip".equalsIgnoreCase(feed);
    }

    /** Compute + store market context for target tickers. Returns the number of new context events. */
    public int ingestAll() {
        if (!enabled) {
            log.info("Market data disabled (app.alpaca.enabled=false)");
            return 0;
        }
        if (!alpaca.hasKeys()) {
            log.info("Market data skipped — no Alpaca keys (set ALPACA_API_KEY_ID / ALPACA_API_SECRET_KEY)");
            return 0;
        }

        // Benchmark once per run for relative strength; RS is simply omitted if SPY can't be fetched.
        MarketFeatures spy = null;
        try {
            spy = MarketFeatures.compute(alpaca.dailyBars(BENCHMARK, lookbackDays));
        } catch (Exception e) {
            log.warn("Could not fetch benchmark {} bars: {}", BENCHMARK, e.getMessage());
        }

        int n = 0;
        for (String ticker : targetTickers()) {
            try {
                n += ingestTicker(ticker, spy);
            } catch (Exception e) {
                log.warn("Market data failed for {}: {}", ticker, e.getMessage());
            }
        }
        log.info("Market data complete — {} new context event(s)", n);
        live.publish("MARKET", n);
        return n;
    }

    private int ingestTicker(String ticker, MarketFeatures spy) {
        String ref = ticker + "|MKT|" + LocalDate.now();
        if (context.existsByTypeAndRef(ContextType.MARKET, ref)) {
            return 0;   // already computed today
        }
        MarketFeatures f = MarketFeatures.compute(alpaca.dailyBars(ticker, lookbackDays));
        if (f == null) {
            return 0;
        }
        ContextEvent c = new ContextEvent();
        c.setTicker(ticker);
        c.setType(ContextType.MARKET);
        c.setSummary(summarize(f, spy));
        c.setRef(ref);
        c.setEventAt(Instant.now());
        context.save(c);
        return 1;
    }

    private String summarize(MarketFeatures f, MarketFeatures spy) {
        StringBuilder sb = new StringBuilder("Momentum: ")
                .append(joinReturns(f.return5d(), f.return20d(), f.return60d()));
        if (spy != null && f.return20d() != null && spy.return20d() != null) {
            sb.append(" · RS vs SPY ").append(signedPct(f.return20d() - spy.return20d())).append(" 20d");
        }
        if (f.avgDollarVolume() != null) {
            sb.append(" · avg vol ").append(volume(f.avgDollarVolume())).append("/day");
            if (!consolidatedVolume) {
                sb.append(" (IEX only)");
            }
        }
        if (f.volatility20d() != null) {
            sb.append(" · 20d vol ").append(Math.round(f.volatility20d())).append("%");
        }
        return sb.toString();
    }

    private static String joinReturns(Double r5, Double r20, Double r60) {
        StringBuilder sb = new StringBuilder();
        if (r5 != null) {
            sb.append(signedPct(r5)).append(" 5d");
        }
        if (r20 != null) {
            sb.append(sb.length() > 0 ? ", " : "").append(signedPct(r20)).append(" 20d");
        }
        if (r60 != null) {
            sb.append(sb.length() > 0 ? ", " : "").append(signedPct(r60)).append(" 60d");
        }
        return sb.length() == 0 ? "n/a" : sb.toString();
    }

    private static String signedPct(double v) {
        return String.format(Locale.US, "%+.1f%%", v);
    }

    private static String volume(double v) {
        if (v >= 1_000_000_000) {
            return String.format(Locale.US, "$%.1fB", v / 1_000_000_000);
        }
        if (v >= 1_000_000) {
            return String.format(Locale.US, "$%.0fM", v / 1_000_000);
        }
        return String.format(Locale.US, "$%.0fK", v / 1_000);
    }

    /** Tracked issuers + your holdings + recently-active tickers, capped. */
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
        return tickers.stream().limit(maxTickers)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
}
