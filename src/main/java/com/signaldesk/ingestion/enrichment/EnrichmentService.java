package com.signaldesk.ingestion.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import com.signaldesk.domain.ContextEvent;
import com.signaldesk.domain.TradeSignal;
import com.signaldesk.domain.TrackedIssuer;
import com.signaldesk.domain.enums.ContextType;
import com.signaldesk.repository.ContextEventRepository;
import com.signaldesk.repository.TrackedIssuerRepository;
import com.signaldesk.repository.TradeSignalRepository;
import com.signaldesk.web.ws.LiveUpdatePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Ingests "context" — the validation layer the AI weighs against trade signals: analyst
 * recommendations, earnings surprises, and fundamentals (Finnhub, free tier) plus 8-K material
 * events (SEC EDGAR). Everything lands in {@code context_event}, deduped by (type, ref).
 */
@Service
public class EnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentService.class);

    private final FinnhubEnrichmentClient finnhub;
    private final EightKClient eightK;
    private final ContextEventRepository context;
    private final TrackedIssuerRepository issuers;
    private final TradeSignalRepository signals;
    private final LiveUpdatePublisher live;

    private final boolean enabled;
    private final int maxTickers;
    private final int eightKPerIssuer;

    public EnrichmentService(FinnhubEnrichmentClient finnhub,
                             EightKClient eightK,
                             ContextEventRepository context,
                             TrackedIssuerRepository issuers,
                             TradeSignalRepository signals,
                             LiveUpdatePublisher live,
                             @Value("${app.enrichment.enabled:true}") boolean enabled,
                             @Value("${app.enrichment.max-tickers:8}") int maxTickers,
                             @Value("${app.enrichment.eightk-per-issuer:3}") int eightKPerIssuer) {
        this.finnhub = finnhub;
        this.eightK = eightK;
        this.context = context;
        this.issuers = issuers;
        this.signals = signals;
        this.live = live;
        this.enabled = enabled;
        this.maxTickers = maxTickers;
        this.eightKPerIssuer = eightKPerIssuer;
    }

    /** Ingest all context sources. Returns the number of new context events stored. */
    public int ingestAll() {
        if (!enabled) {
            log.info("Enrichment disabled (app.enrichment.enabled=false)");
            return 0;
        }
        int total = 0;

        // 8-K material events (free, no key) for tracked issuers.
        for (TrackedIssuer issuer : issuers.findByActiveTrue()) {
            try {
                total += ingest8K(issuer);
            } catch (Exception e) {
                log.warn("8-K enrichment failed for {}: {}", issuer.getTicker(), e.getMessage());
            }
        }

        // Finnhub context (needs the Finnhub key).
        if (finnhub.hasKey()) {
            for (String ticker : targetTickers()) {
                try {
                    total += ingestFinnhub(ticker);
                } catch (Exception e) {
                    log.warn("Finnhub enrichment failed for {}: {}", ticker, e.getMessage());
                }
            }
        } else {
            log.info("Finnhub enrichment skipped — no key (8-K still ingested)");
        }

        log.info("Enrichment complete — {} new context event(s)", total);
        live.publish("ENRICHMENT", total);
        return total;
    }

    private int ingest8K(TrackedIssuer issuer) {
        int n = 0;
        for (EightKClient.EightKRef ref : eightK.fetchRecent(issuer.getCik(), eightKPerIssuer)) {
            if (context.existsByTypeAndRef(ContextType.EIGHT_K, ref.accessionNumber())) {
                continue;
            }
            String items = ref.items() == null || ref.items().isBlank() ? "" : " (items: " + ref.items() + ")";
            save(issuer.getTicker(), ContextType.EIGHT_K,
                    "8-K filed " + ref.filingDate() + items,
                    ref.accessionNumber(),
                    ref.filingDate());
            n++;
        }
        return n;
    }

    private int ingestFinnhub(String ticker) {
        int n = 0;

        JsonNode rec = finnhub.recommendation(ticker);
        if (rec != null && rec.isArray() && rec.size() > 0) {
            JsonNode r = rec.get(0);
            String period = r.path("period").asText("");
            String ref = ticker + "|REC|" + period;
            if (!context.existsByTypeAndRef(ContextType.ANALYST_RATING, ref)) {
                String summary = String.format("Analyst consensus (%s): %d strong-buy, %d buy, %d hold, %d sell, %d strong-sell",
                        period, r.path("strongBuy").asInt(), r.path("buy").asInt(),
                        r.path("hold").asInt(), r.path("sell").asInt(), r.path("strongSell").asInt());
                save(ticker, ContextType.ANALYST_RATING, summary, ref, parse(period));
                n++;
            }
        }

        JsonNode ern = finnhub.earnings(ticker);
        if (ern != null && ern.isArray() && ern.size() > 0) {
            JsonNode e = ern.get(0);
            String period = e.path("period").asText("");
            String ref = ticker + "|ERN|" + period;
            if (!context.existsByTypeAndRef(ContextType.EARNINGS, ref)) {
                String summary = String.format("Earnings %s: EPS actual %s vs est %s (surprise %s%%)",
                        period, e.path("actual").asText("?"), e.path("estimate").asText("?"),
                        e.path("surprisePercent").asText("?"));
                save(ticker, ContextType.EARNINGS, summary, ref, parse(period));
                n++;
            }
        }

        JsonNode met = finnhub.metric(ticker);
        if (met != null) {
            JsonNode m = met.path("metric");
            String today = LocalDate.now().toString();
            String ref = ticker + "|MET|" + today;
            if (!context.existsByTypeAndRef(ContextType.FUNDAMENTAL, ref)) {
                String summary = String.format("Fundamentals: 52-wk range %s–%s, P/E(TTM) %s",
                        m.path("52WeekLow").asText("?"), m.path("52WeekHigh").asText("?"),
                        m.path("peTTM").asText(m.path("peBasicExclExtraTTM").asText("?")));
                save(ticker, ContextType.FUNDAMENTAL, summary, ref, LocalDate.now());
                n++;
            }
        }
        return n;
    }

    private void save(String ticker, ContextType type, String summary, String ref, LocalDate at) {
        ContextEvent c = new ContextEvent();
        c.setTicker(ticker);
        c.setType(type);
        c.setSummary(summary);
        c.setRef(ref);
        if (at != null) {
            c.setEventAt(at.atStartOfDay(ZoneOffset.UTC).toInstant());
        } else {
            c.setEventAt(Instant.now());
        }
        context.save(c);
    }

    private Set<String> targetTickers() {
        Set<String> tickers = new LinkedHashSet<>();
        for (TrackedIssuer i : issuers.findByActiveTrue()) {
            tickers.add(i.getTicker());
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

    private static LocalDate parse(String raw) {
        try {
            return (raw == null || raw.isBlank()) ? null : LocalDate.parse(raw.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
