package com.signaldesk.ingestion.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs market-data ingestion once after startup, then on a slow schedule. */
@Component
public class MarketDataPoller {

    private static final Logger log = LoggerFactory.getLogger(MarketDataPoller.class);

    private final MarketDataService market;
    private final boolean runOnStartup;

    public MarketDataPoller(MarketDataService market,
                            @Value("${app.alpaca.run-on-startup:true}") boolean runOnStartup) {
        this.market = market;
        this.runOnStartup = runOnStartup;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!runOnStartup) {
            return;
        }
        Thread t = new Thread(this::runSafely, "market-startup-ingest");
        t.setDaemon(true);
        t.start();
    }

    @Scheduled(fixedDelayString = "${app.alpaca.interval-ms:21600000}",
            initialDelayString = "${app.alpaca.interval-ms:21600000}")
    public void poll() {
        runSafely();
    }

    private void runSafely() {
        try {
            market.ingestAll();
        } catch (Exception e) {
            log.error("Market data run failed", e);
        }
    }
}
