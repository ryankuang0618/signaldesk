package com.signaldesk.ingestion.edgar;

import com.signaldesk.domain.TradeSignal;
import com.signaldesk.domain.TrackedIssuer;
import com.signaldesk.domain.enums.Side;
import com.signaldesk.domain.enums.TradeSource;
import com.signaldesk.repository.TradeSignalRepository;
import com.signaldesk.repository.TrackedIssuerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** Pulls recent Form 4 filings for tracked issuers and stores them as deduplicated trade signals. */
@Service
public class Form4IngestionService {

    private static final Logger log = LoggerFactory.getLogger(Form4IngestionService.class);

    private final TrackedIssuerRepository issuers;
    private final TradeSignalRepository signals;
    private final EdgarClient edgar;
    private final Form4Parser parser;
    private final int pollLimit;

    public Form4IngestionService(TrackedIssuerRepository issuers,
                                 TradeSignalRepository signals,
                                 EdgarClient edgar,
                                 Form4Parser parser,
                                 @Value("${app.ingestion.poll-limit:8}") int pollLimit) {
        this.issuers = issuers;
        this.signals = signals;
        this.edgar = edgar;
        this.parser = parser;
        this.pollLimit = pollLimit;
    }

    /** Ingest all active issuers. Returns the number of new signals stored. */
    public int ingestAll() {
        int total = 0;
        for (TrackedIssuer issuer : issuers.findByActiveTrue()) {
            total += ingestIssuer(issuer);
        }
        log.info("Form 4 ingestion complete — {} new signal(s)", total);
        return total;
    }

    private int ingestIssuer(TrackedIssuer issuer) {
        List<Form4Ref> refs;
        try {
            refs = edgar.fetchRecentForm4(issuer.getCik(), pollLimit);
        } catch (Exception e) {
            log.warn("Could not list Form 4 filings for {} ({}): {}", issuer.getTicker(), issuer.getCik(), e.getMessage());
            return 0;
        }

        int stored = 0;
        for (Form4Ref ref : refs) {
            if (signals.existsBySourceAndRawRef(TradeSource.INSIDER_FORM4, ref.accessionNumber())) {
                continue;
            }
            try {
                String xml = edgar.fetchForm4Xml(issuer.getCik(), ref);
                Optional<Form4Result> parsed = parser.parse(xml);
                if (parsed.isEmpty()) {
                    continue;
                }
                signals.save(toSignal(issuer, ref, parsed.get()));
                stored++;
            } catch (Exception e) {
                log.warn("Skipping Form 4 {} for {}: {}", ref.accessionNumber(), issuer.getTicker(), e.getMessage());
            }
        }
        if (stored > 0) {
            log.info("{}: stored {} new insider signal(s)", issuer.getTicker(), stored);
        }
        return stored;
    }

    private TradeSignal toSignal(TrackedIssuer issuer, Form4Ref ref, Form4Result r) {
        InsiderQuality.Assessment quality = InsiderQuality.assess(r);

        TradeSignal s = new TradeSignal();
        s.setSource(TradeSource.INSIDER_FORM4);
        s.setTicker(r.issuerSymbol() != null ? r.issuerSymbol() : issuer.getTicker());
        s.setSide(r.signalNet().signum() < 0 ? Side.SELL : Side.BUY);
        s.setActorName(actorLabel(r));
        s.setTransactedAt(r.latestDate());
        s.setDisclosedAt(ref.filingDate().atStartOfDay(ZoneOffset.UTC).toInstant());
        // Graded quality (role × trade type × size) instead of a flat open-market/other confidence.
        s.setConfidence(quality.score());
        s.setNote(quality.note());
        s.setRawRef(ref.accessionNumber());
        return s;
    }

    private String actorLabel(Form4Result r) {
        if (r.ownerName() == null) {
            return null;
        }
        return (r.officerTitle() == null || r.officerTitle().isBlank())
                ? r.ownerName()
                : r.ownerName() + " (" + r.officerTitle() + ")";
    }
}
