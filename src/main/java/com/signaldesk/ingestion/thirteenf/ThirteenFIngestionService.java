package com.signaldesk.ingestion.thirteenf;

import com.signaldesk.domain.CusipTicker;
import com.signaldesk.domain.FundHolding;
import com.signaldesk.domain.TradeSignal;
import com.signaldesk.domain.TrackedActor;
import com.signaldesk.domain.enums.ActorType;
import com.signaldesk.domain.enums.Side;
import com.signaldesk.domain.enums.TradeSource;
import com.signaldesk.ingestion.openfigi.OpenFigiClient;
import com.signaldesk.repository.CusipTickerRepository;
import com.signaldesk.repository.FundHoldingRepository;
import com.signaldesk.repository.TradeSignalRepository;
import com.signaldesk.repository.TrackedActorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Ingests 13F holdings per fund, then derives directional signals by diffing the two most
 * recent quarters. 13F is stale (up to ~45 days), so signals carry low confidence — context,
 * not a live signal. Only changed positions are mapped CUSIP → ticker (cached) to stay within
 * OpenFIGI's limits.
 */
@Service
public class ThirteenFIngestionService {

    private static final Logger log = LoggerFactory.getLogger(ThirteenFIngestionService.class);
    private static final BigDecimal CONFIDENCE = new BigDecimal("0.30");

    private enum ChangeType {
        NEW(Side.BUY), EXIT(Side.SELL), INCREASE(Side.BUY), DECREASE(Side.SELL);
        final Side side;
        ChangeType(Side side) { this.side = side; }
    }

    private record Change(String cusip, ChangeType type, BigDecimal refValue) {}

    private final TrackedActorRepository actors;
    private final FundHoldingRepository holdings;
    private final TradeSignalRepository signals;
    private final CusipTickerRepository cusipCache;
    private final ThirteenFClient client;
    private final ThirteenFParser parser;
    private final OpenFigiClient openFigi;

    private final boolean enabled;
    private final int filingsPerFund;
    private final BigDecimal materialChangePct;
    private final int maxTickerLookups;

    public ThirteenFIngestionService(TrackedActorRepository actors,
                                     FundHoldingRepository holdings,
                                     TradeSignalRepository signals,
                                     CusipTickerRepository cusipCache,
                                     ThirteenFClient client,
                                     ThirteenFParser parser,
                                     OpenFigiClient openFigi,
                                     @Value("${app.thirteenf.enabled:true}") boolean enabled,
                                     @Value("${app.thirteenf.filings-per-fund:2}") int filingsPerFund,
                                     @Value("${app.thirteenf.material-change-pct:20}") int materialChangePct,
                                     @Value("${app.thirteenf.max-ticker-lookups:60}") int maxTickerLookups) {
        this.actors = actors;
        this.holdings = holdings;
        this.signals = signals;
        this.cusipCache = cusipCache;
        this.client = client;
        this.parser = parser;
        this.openFigi = openFigi;
        this.enabled = enabled;
        this.filingsPerFund = filingsPerFund;
        this.materialChangePct = BigDecimal.valueOf(materialChangePct);
        this.maxTickerLookups = maxTickerLookups;
    }

    /** Ingest all FUND actors. Returns the number of new signals stored. */
    public int ingestAll() {
        if (!enabled) {
            log.info("13F ingestion disabled (app.thirteenf.enabled=false)");
            return 0;
        }
        int total = 0;
        for (TrackedActor fund : actors.findByType(ActorType.FUND)) {
            if (fund.getExternalId() == null || fund.getExternalId().isBlank()) {
                continue;
            }
            try {
                total += ingestFund(fund);
            } catch (Exception e) {
                log.warn("13F ingestion failed for {}: {}", fund.getName(), e.getMessage());
            }
        }
        log.info("13F ingestion complete — {} new signal(s)", total);
        return total;
    }

    private int ingestFund(TrackedActor fund) {
        String cik = fund.getExternalId();

        // 1. Store holdings for any recent 13F-HR filings we don't have yet.
        for (Filing13FRef ref : client.fetchRecent(cik, filingsPerFund)) {
            if (holdings.existsByAccession(ref.accessionNumber())) {
                continue;
            }
            String url = client.findInfoTableUrl(cik, ref.accessionNumber());
            if (url == null) {
                continue;
            }
            List<Holding> parsed = parser.parse(client.fetchXml(url));
            for (Holding h : parsed) {
                holdings.save(toHolding(fund, cik, ref, h));
            }
            if (!parsed.isEmpty()) {
                log.info("{}: stored {} holdings for period {}", fund.getName(), parsed.size(), ref.periodOfReport());
            }
        }

        // 2. Diff the two most recent periods and emit signals.
        List<LocalDate> periods = holdings.findPeriodsDesc(fund.getId());
        if (periods.size() < 2) {
            return 0;
        }
        Map<String, FundHolding> latest = index(holdings.findByActorIdAndPeriodOfReport(fund.getId(), periods.get(0)));
        Map<String, FundHolding> prev = index(holdings.findByActorIdAndPeriodOfReport(fund.getId(), periods.get(1)));

        List<Change> changes = diff(latest, prev);
        if (changes.isEmpty()) {
            return 0;
        }
        // Prioritize the largest positions, then bound how many tickers we resolve.
        changes.sort(Comparator.comparing(Change::refValue).reversed());
        List<Change> capped = changes.subList(0, Math.min(changes.size(), maxTickerLookups));
        Map<String, String> tickers = resolveTickers(capped.stream().map(Change::cusip).distinct().toList());

        // A representative filing for disclosure metadata (all latest-period rows share the accession).
        FundHolding anyLatest = latest.values().iterator().next();

        int stored = 0;
        for (Change c : capped) {
            String ticker = tickers.get(c.cusip());
            if (ticker == null) {
                continue;
            }
            String rawRef = anyLatest.getAccession() + "|" + c.cusip() + "|" + c.type();
            if (signals.existsBySourceAndRawRef(TradeSource.THIRTEEN_F, rawRef)) {
                continue;
            }
            signals.save(toSignal(fund, anyLatest, ticker, c, rawRef));
            stored++;
        }
        if (stored > 0) {
            log.info("{}: emitted {} 13F change signal(s)", fund.getName(), stored);
        }
        return stored;
    }

    private List<Change> diff(Map<String, FundHolding> latest, Map<String, FundHolding> prev) {
        List<Change> changes = new ArrayList<>();
        Set<String> all = new HashSet<>();
        all.addAll(latest.keySet());
        all.addAll(prev.keySet());

        for (String cusip : all) {
            FundHolding lat = latest.get(cusip);
            FundHolding pv = prev.get(cusip);
            if (lat != null && pv == null) {
                changes.add(new Change(cusip, ChangeType.NEW, nz(lat.getValue())));
            } else if (lat == null && pv != null) {
                changes.add(new Change(cusip, ChangeType.EXIT, nz(pv.getValue())));
            } else if (lat != null && pv != null) {
                BigDecimal ps = nz(pv.getShares());
                BigDecimal ls = nz(lat.getShares());
                if (ps.signum() == 0) {
                    continue;
                }
                BigDecimal pct = ls.subtract(ps).multiply(BigDecimal.valueOf(100)).divide(ps, 2, RoundingMode.HALF_UP);
                if (pct.compareTo(materialChangePct) >= 0) {
                    changes.add(new Change(cusip, ChangeType.INCREASE, nz(lat.getValue())));
                } else if (pct.compareTo(materialChangePct.negate()) <= 0) {
                    changes.add(new Change(cusip, ChangeType.DECREASE, nz(lat.getValue())));
                }
            }
        }
        return changes;
    }

    /** Resolve tickers using the DB cache first, then OpenFIGI for the rest (persisting results). */
    private Map<String, String> resolveTickers(List<String> cusips) {
        Map<String, String> result = new HashMap<>();
        List<String> toLookup = new ArrayList<>();
        for (String cusip : cusips) {
            Optional<CusipTicker> cached = cusipCache.findById(cusip);
            if (cached.isPresent()) {
                if (cached.get().getTicker() != null) {
                    result.put(cusip, cached.get().getTicker());
                }
            } else {
                toLookup.add(cusip);
            }
        }
        if (!toLookup.isEmpty()) {
            Map<String, String> figi = openFigi.mapCusips(toLookup);
            for (String cusip : toLookup) {
                String ticker = figi.get(cusip);
                cusipCache.save(new CusipTicker(cusip, ticker));   // cache misses too, as null
                if (ticker != null) {
                    result.put(cusip, ticker);
                }
            }
        }
        return result;
    }

    private FundHolding toHolding(TrackedActor fund, String cik, Filing13FRef ref, Holding h) {
        FundHolding fh = new FundHolding();
        fh.setActor(fund);
        fh.setCik(cik);
        fh.setAccession(ref.accessionNumber());
        fh.setPeriodOfReport(ref.periodOfReport());
        fh.setFiledAt(ref.filingDate());
        fh.setCusip(h.cusip());
        fh.setIssuerName(h.issuerName());
        fh.setShares(h.shares());
        fh.setValue(h.value());
        return fh;
    }

    private TradeSignal toSignal(TrackedActor fund, FundHolding latest, String ticker, Change c, String rawRef) {
        TradeSignal s = new TradeSignal();
        s.setSource(TradeSource.THIRTEEN_F);
        s.setTicker(ticker);
        s.setSide(c.type().side);
        s.setActor(fund);
        s.setActorName(fund.getName() + " [" + c.type() + "]");
        // 13F reports positions, not trade dates — leave transactedAt null.
        if (latest.getFiledAt() != null) {
            s.setDisclosedAt(latest.getFiledAt().atStartOfDay(ZoneOffset.UTC).toInstant());
        }
        s.setConfidence(CONFIDENCE);
        s.setRawRef(rawRef);
        return s;
    }

    private static Map<String, FundHolding> index(List<FundHolding> list) {
        Map<String, FundHolding> m = new HashMap<>();
        for (FundHolding h : list) {
            m.put(h.getCusip(), h);
        }
        return m;
    }

    private static BigDecimal nz(BigDecimal b) {
        return b == null ? BigDecimal.ZERO : b;
    }
}
