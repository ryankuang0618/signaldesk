package com.signaldesk.backtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.signaldesk.domain.Briefing;
import com.signaldesk.domain.enums.Side;
import com.signaldesk.ingestion.enrichment.FinnhubEnrichmentClient;
import com.signaldesk.repository.BriefingRepository;
import com.signaldesk.web.ws.LiveUpdatePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Track record: scores each BUY/SELL briefing against the actual price move once its horizon has
 * passed. A BUY is "correct" if the price rose, a SELL if it fell. Aggregated stats reveal whether
 * the AI's calls actually predict — the difference between an opinion generator and a measured edge.
 */
@Service
public class BacktestService {

    private static final Logger log = LoggerFactory.getLogger(BacktestService.class);

    private final BriefingRepository briefings;
    private final FinnhubEnrichmentClient finnhub;
    private final LiveUpdatePublisher live;
    private final int horizonDays;

    public BacktestService(BriefingRepository briefings,
                           FinnhubEnrichmentClient finnhub,
                           LiveUpdatePublisher live,
                           @Value("${app.backtest.horizon-days:7}") int horizonDays) {
        this.briefings = briefings;
        this.finnhub = finnhub;
        this.live = live;
        this.horizonDays = horizonDays;
    }

    /** Evaluate every briefing whose horizon has passed. Returns how many were scored. */
    public int evaluate() {
        if (!finnhub.hasKey()) {
            log.info("Backtest skipped — no Finnhub key for exit prices");
            return 0;
        }
        LocalDate cutoff = LocalDate.now().minusDays(horizonDays);
        int scored = 0;
        for (Briefing b : briefings.findEvaluable(cutoff)) {
            try {
                if (score(b)) {
                    scored++;
                }
            } catch (Exception e) {
                log.warn("Backtest failed for {} #{}: {}", b.getTicker(), b.getId(), e.getMessage());
            }
        }
        if (scored > 0) {
            log.info("Backtest: scored {} briefing(s)", scored);
            live.publish("BACKTEST", scored);
        }
        return scored;
    }

    private boolean score(Briefing b) {
        JsonNode q = finnhub.quote(b.getTicker());
        double exit = q == null ? 0 : q.path("c").asDouble(0);
        if (exit <= 0) {
            return false;
        }
        double entry = b.getEntryPrice().doubleValue();
        if (entry <= 0) {
            return false;
        }
        BigDecimal returnPct = BigDecimal.valueOf((exit - entry) / entry * 100.0).setScale(3, RoundingMode.HALF_UP);
        boolean correct = b.getSignal() == Side.BUY ? returnPct.signum() > 0 : returnPct.signum() < 0;

        b.setExitPrice(BigDecimal.valueOf(exit).setScale(4, RoundingMode.HALF_UP));
        b.setReturnPct(returnPct);
        b.setCorrect(correct);
        b.setEvaluatedAt(Instant.now());
        briefings.save(b);
        return true;
    }

    /** Aggregate track-record stats over evaluated briefings. */
    public Map<String, Object> stats() {
        List<Briefing> done = briefings.findByEvaluatedAtIsNotNullOrderByEvaluatedAtDesc();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("evaluated", done.size());
        if (done.isEmpty()) {
            out.put("hitRate", null);
            out.put("avgReturnPct", null);
            out.put("bySignal", Map.of());
            return out;
        }
        out.put("hitRate", round(hitRate(done)));
        out.put("avgReturnPct", round(avgReturn(done)));

        Map<String, Object> bySignal = new LinkedHashMap<>();
        for (Side s : List.of(Side.BUY, Side.SELL)) {
            List<Briefing> sub = done.stream().filter(b -> b.getSignal() == s).toList();
            if (!sub.isEmpty()) {
                bySignal.put(s.name(), Map.of(
                        "count", sub.size(),
                        "hitRate", round(hitRate(sub)),
                        "avgReturnPct", round(avgReturn(sub))));
            }
        }
        out.put("bySignal", bySignal);
        return out;
    }

    private static double hitRate(List<Briefing> list) {
        long hits = list.stream().filter(b -> Boolean.TRUE.equals(b.getCorrect())).count();
        return 100.0 * hits / list.size();
    }

    private static double avgReturn(List<Briefing> list) {
        // "Directional" return: a correct SELL that dropped 5% is +5% for the call.
        return list.stream()
                .mapToDouble(b -> {
                    double r = b.getReturnPct() == null ? 0 : b.getReturnPct().doubleValue();
                    return b.getSignal() == Side.SELL ? -r : r;
                })
                .average().orElse(0);
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
