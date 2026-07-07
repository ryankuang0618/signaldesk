package com.signaldesk.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.signaldesk.domain.Briefing;
import com.signaldesk.domain.ContextEvent;
import com.signaldesk.domain.NewsItem;
import com.signaldesk.domain.TradeSignal;
import com.signaldesk.domain.enums.Side;
import com.signaldesk.repository.BriefingRepository;
import com.signaldesk.repository.ContextEventRepository;
import com.signaldesk.repository.NewsItemRepository;
import com.signaldesk.repository.TradeSignalRepository;
import com.signaldesk.notify.AlertService;
import com.signaldesk.web.ws.LiveUpdatePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
            - The "signal" you output is your read of the overall lean of the evidence, not a
              recommendation. Use HOLD when the evidence is mixed or thin.

            Respond with STRICT JSON only, no prose outside it, in exactly this shape:
            {"signal":"BUY|SELL|HOLD","confidence":<0.0-1.0>,"summary":"<2-4 sentences>"}
            """;

    private final ClaudeBriefingClient claude;
    private final TradeSignalRepository signals;
    private final NewsItemRepository news;
    private final ContextEventRepository context;
    private final BriefingRepository briefings;
    private final ObjectMapper mapper;
    private final LiveUpdatePublisher live;
    private final AlertService alertService;

    private final boolean enabled;
    private final int maxTickers;
    private final int maxSignals;
    private final int maxNews;
    private final int maxContext;

    public BriefingService(ClaudeBriefingClient claude,
                           TradeSignalRepository signals,
                           NewsItemRepository news,
                           ContextEventRepository context,
                           BriefingRepository briefings,
                           ObjectMapper mapper,
                           LiveUpdatePublisher live,
                           AlertService alertService,
                           @Value("${app.briefing.enabled:true}") boolean enabled,
                           @Value("${app.briefing.max-tickers:8}") int maxTickers,
                           @Value("${app.briefing.max-signals:10}") int maxSignals,
                           @Value("${app.briefing.max-news:8}") int maxNews,
                           @Value("${app.briefing.max-context:8}") int maxContext) {
        this.claude = claude;
        this.signals = signals;
        this.news = news;
        this.context = context;
        this.briefings = briefings;
        this.mapper = mapper;
        this.live = live;
        this.alertService = alertService;
        this.enabled = enabled;
        this.maxTickers = maxTickers;
        this.maxSignals = maxSignals;
        this.maxNews = maxNews;
        this.maxContext = maxContext;
    }

    /** Generate briefings for the most active tickers. Returns how many were produced. */
    public int generateAll() {
        if (!enabled) {
            log.info("Briefing generation disabled (app.briefing.enabled=false)");
            return 0;
        }
        if (!claude.hasKey()) {
            log.info("Briefing generation skipped — no ANTHROPIC_API_KEY set");
            return 0;
        }
        LocalDate today = LocalDate.now();
        int made = 0;
        for (String ticker : targetTickers()) {
            try {
                if (generateForTicker(ticker, today)) {
                    made++;
                }
            } catch (Exception e) {
                log.warn("Briefing failed for {}: {}", ticker, e.getMessage());
            }
        }
        log.info("Briefing generation complete — {} briefing(s) with {}", made, claude.model());
        live.publish("BRIEFING", made);
        // Turn qualifying briefings into alerts (and push to LINE if configured).
        try {
            alertService.processToday();
        } catch (Exception e) {
            log.warn("Alert processing failed: {}", e.getMessage());
        }
        return made;
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

        Briefing b = new Briefing();
        b.setBriefingDate(today);
        b.setTicker(ticker);
        b.setSignal(parseSide(json.path("signal").asText(null)));
        b.setConfidence(clampConfidence(json.path("confidence").asDouble(Double.NaN)));
        b.setSummary(json.path("summary").asText(""));
        b.setModel(claude.model());
        briefings.save(b);
        return true;
    }

    private String buildUserPrompt(String ticker, List<TradeSignal> sig, List<NewsItem> nws) {
        StringBuilder p = new StringBuilder();
        p.append("Ticker: ").append(ticker).append("\n\n");

        p.append("Recent disclosed trades (newest first):\n");
        if (sig.isEmpty()) {
            p.append("  (none)\n");
        } else {
            sig.stream().limit(maxSignals).forEach(s -> p.append("  - [")
                    .append(s.getSource()).append("] ").append(s.getSide())
                    .append(" by ").append(s.getActorName() == null ? "?" : s.getActorName())
                    .append(" (transacted ").append(s.getTransactedAt())
                    .append(", disclosed ").append(s.getDisclosedAt())
                    .append(s.getConfidence() == null ? "" : ", src-confidence " + s.getConfidence())
                    .append(")\n"));
        }

        List<ContextEvent> ctx = context.findByTickerOrderByEventAtDesc(ticker);
        p.append("\nContext — analyst ratings, earnings, 8-K, fundamentals (newest first):\n");
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
                .append(". Weigh the trade signals against the context (analyst ratings, earnings, 8-K, ")
                .append("fundamentals) and the news, accounting for freshness. Return strict JSON only.");
        return p.toString();
    }

    private Set<String> targetTickers() {
        Set<String> tickers = new LinkedHashSet<>();
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
