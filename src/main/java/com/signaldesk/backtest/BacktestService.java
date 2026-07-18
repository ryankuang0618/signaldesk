package com.signaldesk.backtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.signaldesk.domain.Briefing;
import com.signaldesk.domain.BriefingScore;
import com.signaldesk.domain.enums.Horizon;
import com.signaldesk.domain.enums.Side;
import com.signaldesk.ingestion.enrichment.FinnhubEnrichmentClient;
import com.signaldesk.repository.BriefingScoreRepository;
import com.signaldesk.web.ws.LiveUpdatePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Track record, scored per horizon. When a briefing is made, each directional (BUY/SELL) horizon —
 * short (days), swing (weeks), long (months) — gets a pending {@link BriefingScore}. Once a score's
 * own window has passed, it's graded against the price move: a BUY is "correct" if the price rose, a
 * SELL if it fell. Aggregated stats reveal whether each horizon's calls actually predict.
 */
@Service
public class BacktestService {

    private static final Logger log = LoggerFactory.getLogger(BacktestService.class);

    private final BriefingScoreRepository scores;
    private final FinnhubEnrichmentClient finnhub;
    private final LiveUpdatePublisher live;
    private final Map<Horizon, Integer> horizonDays;

    public BacktestService(BriefingScoreRepository scores,
                           FinnhubEnrichmentClient finnhub,
                           LiveUpdatePublisher live,
                           @Value("${app.backtest.short-horizon-days:5}") int shortDays,
                           @Value("${app.backtest.swing-horizon-days:15}") int swingDays,
                           @Value("${app.backtest.long-horizon-days:60}") int longDays) {
        this.scores = scores;
        this.finnhub = finnhub;
        this.live = live;
        this.horizonDays = new EnumMap<>(Horizon.class);
        this.horizonDays.put(Horizon.SHORT, shortDays);
        this.horizonDays.put(Horizon.SWING, swingDays);
        this.horizonDays.put(Horizon.LONG, longDays);
    }

    /**
     * Create pending score rows for a freshly-made briefing — one per directional horizon. Called
     * after the briefing is saved; no-op if no entry price was captured (nothing to score against).
     */
    public void scheduleScores(Briefing b) {
        BigDecimal entry = b.getEntryPrice();
        if (b.getId() == null || entry == null || entry.signum() <= 0) {
            return;
        }
        schedule(b, Horizon.SHORT, b.getShortSignal(), entry);
        schedule(b, Horizon.SWING, b.getSignal(), entry);   // swing is the headline signal
        schedule(b, Horizon.LONG, b.getLongSignal(), entry);
    }

    private void schedule(Briefing b, Horizon h, Side signal, BigDecimal entry) {
        if (signal != Side.BUY && signal != Side.SELL) {
            return;   // only directional calls can be right/wrong on a price move
        }
        int days = horizonDays.get(h);
        BriefingScore s = new BriefingScore();
        s.setBriefingId(b.getId());
        s.setTicker(b.getTicker());
        s.setHorizon(h);
        s.setSignal(signal);
        s.setHorizonDays(days);
        s.setEntryPrice(entry);
        s.setDueDate(b.getBriefingDate().plusDays(days));
        scores.save(s);
    }

    /** Evaluate every score whose horizon window has passed. Returns how many were scored. */
    public int evaluate() {
        if (!finnhub.hasKey()) {
            log.info("Backtest skipped — no Finnhub key for exit prices");
            return 0;
        }
        int scored = 0;
        for (BriefingScore s : scores.findByEvaluatedAtIsNullAndDueDateLessThanEqual(LocalDate.now())) {
            try {
                if (score(s)) {
                    scored++;
                }
            } catch (Exception e) {
                log.warn("Backtest failed for {} {} #{}: {}", s.getTicker(), s.getHorizon(), s.getId(), e.getMessage());
            }
        }
        if (scored > 0) {
            log.info("Backtest: scored {} horizon call(s)", scored);
            live.publish("BACKTEST", scored);
        }
        return scored;
    }

    private boolean score(BriefingScore s) {
        JsonNode q = finnhub.quote(s.getTicker());
        double exit = q == null ? 0 : q.path("c").asDouble(0);
        if (exit <= 0) {
            return false;
        }
        double entry = s.getEntryPrice().doubleValue();
        if (entry <= 0) {
            return false;
        }
        BigDecimal returnPct = BigDecimal.valueOf((exit - entry) / entry * 100.0).setScale(3, RoundingMode.HALF_UP);
        boolean correct = s.getSignal() == Side.BUY ? returnPct.signum() > 0 : returnPct.signum() < 0;

        s.setExitPrice(BigDecimal.valueOf(exit).setScale(4, RoundingMode.HALF_UP));
        s.setReturnPct(returnPct);
        s.setCorrect(correct);
        s.setEvaluatedAt(Instant.now());
        scores.save(s);
        return true;
    }

    /** Aggregate track-record stats over evaluated calls, broken down by horizon. */
    public Map<String, Object> stats() {
        List<BriefingScore> done = scores.findByEvaluatedAtIsNotNullOrderByEvaluatedAtDesc();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("evaluated", done.size());
        if (done.isEmpty()) {
            out.put("hitRate", null);
            out.put("avgReturnPct", null);
            out.put("byHorizon", Map.of());
            return out;
        }
        out.put("hitRate", round(hitRate(done)));
        out.put("avgReturnPct", round(avgReturn(done)));

        Map<String, Object> byHorizon = new LinkedHashMap<>();
        for (Horizon h : Horizon.values()) {
            List<BriefingScore> sub = done.stream().filter(s -> s.getHorizon() == h).toList();
            if (!sub.isEmpty()) {
                byHorizon.put(h.name(), Map.of(
                        "count", sub.size(),
                        "hitRate", round(hitRate(sub)),
                        "avgReturnPct", round(avgReturn(sub))));
            }
        }
        out.put("byHorizon", byHorizon);
        return out;
    }

    private static double hitRate(List<BriefingScore> list) {
        long hits = list.stream().filter(s -> Boolean.TRUE.equals(s.getCorrect())).count();
        return 100.0 * hits / list.size();
    }

    private static double avgReturn(List<BriefingScore> list) {
        // "Directional" return: a correct SELL that dropped 5% is +5% for the call.
        return list.stream()
                .mapToDouble(s -> {
                    double r = s.getReturnPct() == null ? 0 : s.getReturnPct().doubleValue();
                    return s.getSignal() == Side.SELL ? -r : r;
                })
                .average().orElse(0);
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
