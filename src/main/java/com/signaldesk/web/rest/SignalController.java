package com.signaldesk.web.rest;

import com.signaldesk.domain.TradeSignal;
import com.signaldesk.domain.enums.TradeSource;
import com.signaldesk.ingestion.congress.CongressIngestionService;
import com.signaldesk.ingestion.edgar.Form4IngestionService;
import com.signaldesk.ingestion.thirteenf.ThirteenFIngestionService;
import com.signaldesk.repository.TradeSignalRepository;
import com.signaldesk.web.ws.LiveUpdatePublisher;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Read trade signals, and manually trigger ingestion runs. */
@RestController
@RequestMapping("/api/signals")
public class SignalController {

    private final TradeSignalRepository signals;
    private final Form4IngestionService insiderIngestion;
    private final CongressIngestionService congressIngestion;
    private final ThirteenFIngestionService thirteenFIngestion;
    private final LiveUpdatePublisher live;

    public SignalController(TradeSignalRepository signals,
                            Form4IngestionService insiderIngestion,
                            CongressIngestionService congressIngestion,
                            ThirteenFIngestionService thirteenFIngestion,
                            LiveUpdatePublisher live) {
        this.signals = signals;
        this.insiderIngestion = insiderIngestion;
        this.congressIngestion = congressIngestion;
        this.thirteenFIngestion = thirteenFIngestion;
        this.live = live;
    }

    /** Latest signals, optionally filtered by ticker and/or source (INSIDER_FORM4, CONGRESS, ...). */
    @GetMapping
    public List<TradeSignal> list(@RequestParam(required = false) String ticker,
                                  @RequestParam(required = false) TradeSource source) {
        if (ticker != null && !ticker.isBlank()) {
            return signals.findByTickerOrderByDisclosedAtDesc(ticker.toUpperCase());
        }
        if (source != null) {
            return signals.findTop100BySourceOrderByDisclosedAtDesc(source);
        }
        return signals.findTop100ByOrderByDisclosedAtDesc();
    }

    /** Kick off both ingestion runs now (handy while developing). */
    @PostMapping("/refresh")
    public Map<String, Object> refresh() {
        int insider = insiderIngestion.ingestAll();
        int congress = congressIngestion.ingestAll();
        int thirteenF = thirteenFIngestion.ingestAll();
        live.publish("MANUAL", insider + congress + thirteenF);
        return Map.of(
                "newInsiderSignals", insider,
                "newCongressSignals", congress,
                "new13FSignals", thirteenF,
                "total", signals.count());
    }
}
