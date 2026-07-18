package com.signaldesk.ingestion.ownership;

import com.signaldesk.domain.TrackedActor;
import com.signaldesk.domain.TradeSignal;
import com.signaldesk.domain.enums.ActorType;
import com.signaldesk.domain.enums.Side;
import com.signaldesk.domain.enums.TradeSource;
import com.signaldesk.repository.TradeSignalRepository;
import com.signaldesk.repository.TrackedActorRepository;
import com.signaldesk.web.ws.LiveUpdatePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

/**
 * Ingests Schedule 13D/13G filings by tracked funds into {@code trade_signal}. A 13D is an activist
 * &gt;5% stake (strong signal); a 13G is a passive &gt;5% holding (weaker). Both are accumulation
 * events, so they're recorded as BUY.
 *
 * <p>v1 limitation: the exact stake percentage and, for amendments, the direction (added vs. sold
 * down) live on the filing's cover page and aren't parsed yet — amendments get lower confidence and
 * a note flagging the direction is unverified.
 */
@Service
public class Schedule13IngestionService {

    private static final Logger log = LoggerFactory.getLogger(Schedule13IngestionService.class);

    private final Schedule13Client client;
    private final TradeSignalRepository signals;
    private final TrackedActorRepository actors;
    private final LiveUpdatePublisher live;

    private final boolean enabled;
    private final int perFundLimit;

    public Schedule13IngestionService(Schedule13Client client,
                                      TradeSignalRepository signals,
                                      TrackedActorRepository actors,
                                      LiveUpdatePublisher live,
                                      @Value("${app.schedule13.enabled:true}") boolean enabled,
                                      @Value("${app.schedule13.per-fund-limit:8}") int perFundLimit) {
        this.client = client;
        this.signals = signals;
        this.actors = actors;
        this.live = live;
        this.enabled = enabled;
        this.perFundLimit = perFundLimit;
    }

    /** Ingest 13D/13G for every tracked fund. Returns the number of new signals stored. */
    public int ingestAll() {
        if (!enabled) {
            log.info("13D/13G ingestion disabled (app.schedule13.enabled=false)");
            return 0;
        }
        int total = 0;
        for (TrackedActor fund : actors.findByType(ActorType.FUND)) {
            if (fund.isActive() && fund.getExternalId() != null && !fund.getExternalId().isBlank()) {
                total += ingestFund(fund);
            }
        }
        log.info("13D/13G ingestion complete — {} new signal(s)", total);
        if (total > 0) {
            live.publish("SCHEDULE_13D", total);
        }
        return total;
    }

    private int ingestFund(TrackedActor fund) {
        List<Schedule13Ref> refs;
        try {
            refs = client.fetchRecent(fund.getExternalId(), perFundLimit);
        } catch (Exception e) {
            log.warn("Could not list 13D/13G for {} ({}): {}", fund.getName(), fund.getExternalId(), e.getMessage());
            return 0;
        }

        int stored = 0;
        for (Schedule13Ref ref : refs) {
            if (signals.existsBySourceAndRawRef(TradeSource.SCHEDULE_13D, ref.accessionNumber())) {
                continue;
            }
            try {
                Schedule13Client.Subject subject = client.resolveSubject(fund.getExternalId(), ref);
                if (subject == null || subject.ticker() == null || subject.ticker().isBlank()) {
                    // No resolvable ticker (foreign issuer, ETF, etc.) — nothing to brief on; skip.
                    continue;
                }
                signals.save(toSignal(fund, ref, subject));
                stored++;
            } catch (Exception e) {
                log.warn("Skipping 13D/13G {} for {}: {}", ref.accessionNumber(), fund.getName(), e.getMessage());
            }
        }
        if (stored > 0) {
            log.info("{}: stored {} new 13D/13G signal(s)", fund.getName(), stored);
        }
        return stored;
    }

    private TradeSignal toSignal(TrackedActor fund, Schedule13Ref ref, Schedule13Client.Subject subject) {
        TradeSignal s = new TradeSignal();
        s.setSource(TradeSource.SCHEDULE_13D);
        s.setTicker(subject.ticker().toUpperCase(Locale.ROOT));
        s.setSide(Side.BUY);   // a >5% stake disclosure is an accumulation event
        s.setActorName(fund.getName());
        s.setTransactedAt(ref.filingDate());
        s.setDisclosedAt(ref.filingDate().atStartOfDay(ZoneOffset.UTC).toInstant());
        s.setConfidence(confidence(ref));
        s.setNote(note(ref));
        s.setRawRef(ref.accessionNumber());
        return s;
    }

    private static BigDecimal confidence(Schedule13Ref ref) {
        if (ref.isActivist()) {
            return ref.isAmendment() ? new BigDecimal("0.35") : new BigDecimal("0.60");
        }
        return ref.isAmendment() ? new BigDecimal("0.20") : new BigDecimal("0.30");
    }

    private static String note(Schedule13Ref ref) {
        if (ref.isAmendment()) {
            String kind = ref.isActivist() ? "Schedule 13D" : "Schedule 13G";
            return kind + " amendment — stake change (direction unverified)";
        }
        return ref.isActivist()
                ? "Schedule 13D — activist 5%+ stake"
                : "Schedule 13G — passive 5%+ stake";
    }
}
