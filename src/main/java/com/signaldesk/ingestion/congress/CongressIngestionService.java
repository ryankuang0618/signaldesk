package com.signaldesk.ingestion.congress;

import com.signaldesk.domain.TradeSignal;
import com.signaldesk.domain.enums.Side;
import com.signaldesk.domain.enums.TradeSource;
import com.signaldesk.repository.TradeSignalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;

/** Pulls recent Congress trades from FMP and stores them as deduplicated trade signals. */
@Service
public class CongressIngestionService {

    private static final Logger log = LoggerFactory.getLogger(CongressIngestionService.class);
    private static final List<String> CHAMBERS = List.of("senate-latest", "house-latest");
    // Congress trades are real transactions but delayed and noisy — a moderate default confidence.
    private static final BigDecimal CONFIDENCE = new BigDecimal("0.60");

    private final CongressClient client;
    private final TradeSignalRepository signals;
    private final boolean enabled;
    private final int pages;
    private final int pageSize;

    public CongressIngestionService(CongressClient client,
                                    TradeSignalRepository signals,
                                    @Value("${app.congress.enabled:true}") boolean enabled,
                                    @Value("${app.congress.pages:2}") int pages,
                                    @Value("${app.congress.page-size:100}") int pageSize) {
        this.client = client;
        this.signals = signals;
        this.enabled = enabled;
        this.pages = pages;
        this.pageSize = pageSize;
    }

    /** Ingest recent trades from both chambers. Returns the number of new signals stored. */
    public int ingestAll() {
        if (!enabled) {
            log.info("Congress ingestion disabled (app.congress.enabled=false)");
            return 0;
        }
        if (!client.hasApiKey()) {
            log.info("Congress ingestion skipped — no FMP API key set (FMP_API_KEY / app.congress.api-key)");
            return 0;
        }

        int total = 0;
        for (String chamber : CHAMBERS) {
            for (int page = 0; page < pages; page++) {
                try {
                    total += ingestPage(chamber, page);
                } catch (Exception e) {
                    log.warn("Congress ingestion failed for {} page {}: {}", chamber, page, e.getMessage());
                }
            }
        }
        log.info("Congress ingestion complete — {} new signal(s)", total);
        return total;
    }

    private int ingestPage(String chamber, int page) {
        List<CongressTrade> trades = client.fetchLatest(chamber, page, pageSize);
        int stored = 0;
        for (CongressTrade t : trades) {
            Side side = mapSide(t.type());
            if (side == null || !isStock(t.assetType()) || isBlankSymbol(t.symbol())) {
                continue;   // skip non-stock, options, exchanges, and un-tickered assets
            }
            String rawRef = dedupKey(t);
            if (signals.existsBySourceAndRawRef(TradeSource.CONGRESS, rawRef)) {
                continue;
            }
            signals.save(toSignal(t, side, rawRef));
            stored++;
        }
        if (stored > 0) {
            log.info("{} page {}: stored {} new Congress signal(s)", chamber, page, stored);
        }
        return stored;
    }

    private TradeSignal toSignal(CongressTrade t, Side side, String rawRef) {
        TradeSignal s = new TradeSignal();
        s.setSource(TradeSource.CONGRESS);
        s.setTicker(t.symbol().trim().toUpperCase());
        s.setSide(side);
        s.setActorName(actorLabel(t));
        s.setTransactedAt(parseDate(t.transactionDate()));
        LocalDate disclosed = parseDate(t.disclosureDate());
        if (disclosed != null) {
            s.setDisclosedAt(disclosed.atStartOfDay(ZoneOffset.UTC).toInstant());
        }
        s.setConfidence(CONFIDENCE);
        s.setRawRef(rawRef);
        return s;
    }

    private static Side mapSide(String type) {
        if (type == null) {
            return null;
        }
        String t = type.trim().toLowerCase();
        if (t.startsWith("purchase")) {
            return Side.BUY;
        }
        if (t.startsWith("sale")) {
            return Side.SELL;
        }
        return null;   // "Exchange" and anything else
    }

    private static boolean isStock(String assetType) {
        return assetType != null && assetType.toLowerCase().contains("stock");
    }

    private static boolean isBlankSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return true;
        }
        String s = symbol.trim();
        return s.equals("-") || s.equalsIgnoreCase("N/A");
    }

    private static String actorLabel(CongressTrade t) {
        String name = ((nz(t.firstName()) + " " + nz(t.lastName())).trim());
        if (name.isBlank()) {
            name = "Unknown";
        }
        return (t.district() == null || t.district().isBlank()) ? name : name + " (" + t.district() + ")";
    }

    /** Stable dedup key — the source data has no per-trade id. */
    private static String dedupKey(CongressTrade t) {
        String composite = String.join("|",
                nz(t.link()), nz(t.symbol()), nz(t.transactionDate()), nz(t.type()), nz(t.lastName()));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(composite.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return composite.length() > 255 ? composite.substring(0, 255) : composite;
        }
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
