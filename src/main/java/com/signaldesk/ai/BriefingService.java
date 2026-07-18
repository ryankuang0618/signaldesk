package com.signaldesk.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.signaldesk.backtest.BacktestService;
import com.signaldesk.domain.Briefing;
import com.signaldesk.domain.ContextEvent;
import com.signaldesk.domain.NewsItem;
import com.signaldesk.domain.PortfolioPosition;
import com.signaldesk.domain.TradeSignal;
import com.signaldesk.domain.enums.PositionSource;
import com.signaldesk.domain.enums.Side;
import com.signaldesk.domain.enums.TradeSource;
import com.signaldesk.ingestion.macro.MacroService;
import com.signaldesk.repository.BriefingRepository;
import com.signaldesk.repository.ContextEventRepository;
import com.signaldesk.repository.NewsItemRepository;
import com.signaldesk.repository.PortfolioPositionRepository;
import com.signaldesk.repository.TradeSignalRepository;
import com.signaldesk.notify.AlertService;
import com.signaldesk.web.ws.LiveUpdatePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Generates per-ticker research briefings: gathers recent signals + news for a ticker, asks Claude
 * to weigh them, and stores a labeled {@link Briefing}. Explicitly research, not financial advice.
 */
@Service
public class BriefingService {

    private static final Logger log = LoggerFactory.getLogger(BriefingService.class);

    private static final String SYSTEM_PROMPT = """
            You are a research assistant for a personal stock-research tool. You analyze disclosed
            trading activity by notable actors — corporate insiders (SEC Form 4), members of Congress,
            and hedge funds (13F) — alongside recent company news, and produce a concise research
            briefing for a single ticker.

            Important context:
            - This is RESEARCH, not financial advice. Never tell the user to buy or sell.
            - The trade data is DELAYED by disclosure rules: 13F is up to ~45 days old, insider Form 4
              ~2 days, Congress days-to-weeks. Weigh freshness accordingly.
            - A "signal" is your read of the lean of the evidence, not a recommendation. Use HOLD when
              the evidence is mixed or thin.
            - Insider trades carry a quality note (role, trade type, size). Weight a CEO/CFO open-market
              purchase heavily; treat grants, option exercises and tax-withholding sales as near-noise.
            - Judge news by EVENT TYPE and impact, not generic sentiment. High-impact events (guidance
              change, M&A, regulatory/legal action, executive departure, major customer win/loss,
              capital raise, buyback, dividend change, clinical/FDA outcome) should move your read far
              more than routine coverage. Name the driving event(s) in the summary.

            Give a SEPARATE read for three time horizons, because the inputs act over different spans:
            - short_term (days): weigh recent news, 8-K events, earnings surprises, sudden analyst
              moves and the price reaction. Old disclosures matter little here.
            - swing (weeks): weigh insider buying/selling (especially clusters), analyst target
              changes, and price momentum.
            - long_term (months): weigh 13F fund positioning and fundamentals; freshness matters least.
            It is NORMAL for the horizons to disagree (e.g. long-term accumulate but short-term wait) —
            express that in the summary rather than averaging it away.

            Respond with STRICT JSON only, no prose outside it, in exactly this shape:
            {"short_term":{"signal":"BUY|SELL|HOLD","confidence":<0.0-1.0>},
             "swing":{"signal":"BUY|SELL|HOLD","confidence":<0.0-1.0>},
             "long_term":{"signal":"BUY|SELL|HOLD","confidence":<0.0-1.0>},
             "summary":"<2-4 sentences, noting any cross-horizon disagreement>"}
            """;

    private final ClaudeBriefingClient claude;
    private final TradeSignalRepository signals;
    private final NewsItemRepository news;
    private final ContextEventRepository context;
    private final BriefingRepository briefings;
    private final PortfolioPositionRepository positions;
    private final ObjectMapper mapper;
    private final LiveUpdatePublisher live;
    private final AlertService alertService;
    private final BriefingJobRegistry jobs;
    private final BacktestService backtest;
    private final MacroService macro;

    private final boolean enabled;
    private final int maxTickers;
    private final int maxSignals;
    private final int maxNews;
    private final int maxContext;
    private final ExecutorService workers;

    public BriefingService(ClaudeBriefingClient claude,
                           TradeSignalRepository signals,
                           NewsItemRepository news,
                           ContextEventRepository context,
                           BriefingRepository briefings,
                           PortfolioPositionRepository positions,
                           ObjectMapper mapper,
                           LiveUpdatePublisher live,
                           AlertService alertService,
                           BriefingJobRegistry jobs,
                           BacktestService backtest,
                           MacroService macro,
                           @Value("${app.briefing.enabled:true}") boolean enabled,
                           @Value("${app.briefing.max-tickers:8}") int maxTickers,
                           @Value("${app.briefing.max-signals:10}") int maxSignals,
                           @Value("${app.briefing.max-news:8}") int maxNews,
                           @Value("${app.briefing.max-context:8}") int maxContext,
                           @Value("${app.briefing.max-concurrency:4}") int maxConcurrency) {
        this.claude = claude;
        this.signals = signals;
        this.news = news;
        this.context = context;
        this.briefings = briefings;
        this.positions = positions;
        this.mapper = mapper;
        this.live = live;
        this.alertService = alertService;
        this.jobs = jobs;
        this.backtest = backtest;
        this.macro = macro;
        this.enabled = enabled;
        this.maxTickers = maxTickers;
        this.maxSignals = maxSignals;
        this.maxNews = maxNews;
        this.maxContext = maxContext;
        this.workers = Executors.newFixedThreadPool(Math.max(1, maxConcurrency));
    }

    /** Most recent price for a ticker, read from the latest TECHNICAL context event's payload. */
    private BigDecimal latestPrice(String ticker) {
        return context.findByTickerOrderByEventAtDesc(ticker).stream()
                .filter(c -> c.getType() == com.signaldesk.domain.enums.ContextType.TECHNICAL && c.getPayload() != null)
                .findFirst()
                .map(c -> {
                    try {
                        JsonNode p = mapper.readTree(c.getPayload());
                        return p.has("price") ? new BigDecimal(p.get("price").asText()) : null;
                    } catch (Exception e) {
                        return null;
                    }
                })
                .orElse(null);
    }

    public Optional<BriefingJob> findJob(String jobId) {
        return jobs.find(jobId);
    }

    /**
     * Create a background generation job and return it immediately. Poll {@link #findJob(String)}
     * (or GET /api/briefings/jobs/{jobId}) until status is terminal.
     */
    public BriefingJob startJob() {
        BriefingJob job = jobs.create(claude.model());
        if (!enabled) {
            job.skip("disabled");
            return job;
        }
        if (!claude.hasKey()) {
            job.skip("no_api_key");
            return job;
        }
        CompletableFuture.runAsync(() -> runJob(job));
        return job;
    }

    private void runJob(BriefingJob job) {
        try {
            LocalDate today = LocalDate.now();
            List<String> tickers = new ArrayList<>(targetTickers());
            job.begin(tickers.size());

            tickers.stream()
                    .map(ticker -> CompletableFuture.runAsync(() -> {
                        try {
                            job.tickCompleted(generateForTicker(ticker, today));
                        } catch (Exception e) {
                            job.tickCompleted(false);
                            log.warn("Briefing failed for {}: {}", ticker, e.getMessage());
                        }
                    }, workers))
                    .forEach(CompletableFuture::join);

            int made = job.getGenerated();
            log.info("Briefing job {} complete — {} briefing(s) with {}", job.getId(), made, claude.model());
            job.succeed();
            live.publish("BRIEFING", made);
            try {
                alertService.processToday();
            } catch (Exception e) {
                log.warn("Alert processing failed: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.warn("Briefing job {} failed: {}", job.getId(), e.getMessage());
            job.fail(e.getMessage());
        }
    }

    private boolean generateForTicker(String ticker, LocalDate today) {
        List<TradeSignal> sig = signals.findByTickerOrderByDisclosedAtDesc(ticker);
        List<NewsItem> nws = news.findByTickerOrderByPublishedAtDesc(ticker);
        if (sig.isEmpty() && nws.isEmpty()) {
            return false;
        }

        String raw = claude.complete(SYSTEM_PROMPT, buildUserPrompt(ticker, sig, nws));
        JsonNode json = extractJson(raw);
        if (json == null) {
            log.warn("Briefing for {} — could not parse JSON from model output", ticker);
            return false;
        }

        // Regenerate cleanly: replace any earlier briefing for this ticker today.
        briefings.deleteByTickerAndBriefingDate(ticker, today);

        // Swing is the headline (drives alerts, the portfolio line, and the backtest); short/long
        // are shown on the ticker detail view.
        HorizonRead swing = readHorizon(json, "swing");
        HorizonRead shortTerm = readHorizon(json, "short_term");
        HorizonRead longTerm = readHorizon(json, "long_term");

        Briefing b = new Briefing();
        b.setBriefingDate(today);
        b.setTicker(ticker);
        b.setSignal(swing.side());
        b.setConfidence(swing.confidence());
        b.setShortSignal(shortTerm.side());
        b.setShortConfidence(shortTerm.confidence());
        b.setLongSignal(longTerm.side());
        b.setLongConfidence(longTerm.confidence());
        b.setSummary(json.path("summary").asText(""));
        b.setModel(claude.model());
        // Capture the entry price so the backtest can score each horizon later.
        b.setEntryPrice(latestPrice(ticker));
        b = briefings.save(b);
        backtest.scheduleScores(b);
        return true;
    }

    private String buildUserPrompt(String ticker, List<TradeSignal> sig, List<NewsItem> nws) {
        StringBuilder p = new StringBuilder();
        p.append("Ticker: ").append(ticker).append("\n\n");

        String regime = macro.regime();
        if (regime != null && !regime.isBlank()) {
            p.append(regime).append("\n\n");
        }

        p.append("Recent disclosed trades (newest first):\n");
        if (sig.isEmpty()) {
            p.append("  (none)\n");
        } else {
            sig.stream().limit(maxSignals).forEach(s -> p.append("  - [")
                    .append(s.getSource()).append("] ").append(s.getSide())
                    .append(" by ").append(s.getActorName() == null ? "?" : s.getActorName())
                    .append(s.getNote() == null ? "" : " — " + s.getNote())
                    .append(" (transacted ").append(s.getTransactedAt())
                    .append(", disclosed ").append(s.getDisclosedAt())
                    .append(s.getConfidence() == null ? "" : ", quality " + s.getConfidence())
                    .append(")\n"));
        }

        // Insider-cluster signal: multiple distinct insiders acting the same way is far stronger
        // than any single Form 4. (3+ open-market buyers in 90 days is a classic bullish cluster.)
        Instant since = Instant.now().minus(90, ChronoUnit.DAYS);
        long buyers = signals.countDistinctActors(ticker, TradeSource.INSIDER_FORM4, Side.BUY, since);
        long sellers = signals.countDistinctActors(ticker, TradeSource.INSIDER_FORM4, Side.SELL, since);
        p.append("\nInsider cluster (last 90 days): ").append(buyers)
                .append(" distinct insiders bought, ").append(sellers).append(" sold");
        if (buyers >= 3) {
            p.append(" — CLUSTER BUYING (a strong bullish signal)");
        } else if (sellers >= 3) {
            p.append(" — cluster selling");
        }
        p.append(".\n");

        List<ContextEvent> ctx = context.findByTickerOrderByEventAtDesc(ticker);
        p.append("\nContext — analyst ratings, earnings, 8-K, fundamentals, price, and market "
                + "momentum/relative-strength/liquidity (newest first):\n");
        if (ctx.isEmpty()) {
            p.append("  (none)\n");
        } else {
            ctx.stream().limit(maxContext).forEach(c -> p.append("  - [")
                    .append(c.getType()).append("] ").append(c.getSummary()).append("\n"));
        }

        p.append("\nRecent news headlines (newest first):\n");
        if (nws.isEmpty()) {
            p.append("  (none)\n");
        } else {
            nws.stream().limit(maxNews).forEach(n -> p.append("  - ")
                    .append(n.getHeadline())
                    .append(" (").append(n.getSource()).append(", ").append(n.getPublishedAt()).append(")\n"));
        }

        p.append("\nSynthesize a research briefing for ").append(ticker)
                .append(". Weigh the trade signals (note any insider cluster) against the context — ")
                .append("analyst ratings, earnings, fundamentals, where the price sits in its 52-week ")
                .append("range, and its momentum and relative strength vs the market (has news already ")
                .append("been priced in?) — the news, accounting for freshness, and the market regime ")
                .append("backdrop (size conviction down in a risk-off tape). Give a short_term, ")
                .append("swing, and long_term read as instructed. Return strict JSON only.");
        return p.toString();
    }

    private Set<String> targetTickers() {
        Set<String> tickers = new LinkedHashSet<>();
        // Always brief what you own first (bypassing the cap) so 'holdings' gets a fresh daily read
        // per position even when your stocks are quiet market-wide.
        for (PortfolioPosition p : positions.findBySource(PositionSource.MANUAL)) {
            if (p.getTicker() != null) {
                tickers.add(p.getTicker().toUpperCase());
            }
        }
        // Then fill with the market's most-active tickers, up to the cap (counting held tickers).
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

    /** Tolerantly pull the first {...} JSON object out of the model's text. */
    private JsonNode extractJson(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return mapper.readTree(raw.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    /** One horizon's parsed read. */
    private record HorizonRead(Side side, BigDecimal confidence) {}

    /**
     * Pull a horizon's {signal, confidence} from the model output. Falls back to the old single-signal
     * shape ({"signal","confidence"} at top level) for the swing horizon, so pre-change output and any
     * model that ignores the new shape still produce a usable headline.
     */
    private HorizonRead readHorizon(JsonNode json, String key) {
        JsonNode node = json.path(key);
        if (node.isObject()) {
            return new HorizonRead(parseSide(node.path("signal").asText(null)),
                    clampConfidence(node.path("confidence").asDouble(Double.NaN)));
        }
        if ("swing".equals(key) && json.has("signal")) {
            return new HorizonRead(parseSide(json.path("signal").asText(null)),
                    clampConfidence(json.path("confidence").asDouble(Double.NaN)));
        }
        return new HorizonRead(Side.HOLD, null);
    }

    private static Side parseSide(String s) {
        if (s == null) {
            return Side.HOLD;
        }
        try {
            return Side.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Side.HOLD;
        }
    }

    private static BigDecimal clampConfidence(double v) {
        if (Double.isNaN(v)) {
            return null;
        }
        double c = Math.max(0.0, Math.min(1.0, v));
        return BigDecimal.valueOf(Math.round(c * 1000) / 1000.0);
    }
}
