package com.signaldesk.ingestion.enrichment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs enrichment once after startup, then on a slow schedule. */
@Component
public class EnrichmentPoller {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentPoller.class);

    private final EnrichmentService ingestion;
    private final boolean runOnStartup;

    public EnrichmentPoller(EnrichmentService ingestion,
                            @Value("${app.enrichment.run-on-startup:true}") boolean runOnStartup) {
        this.ingestion = ingestion;
        this.runOnStartup = runOnStartup;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!runOnStartup) {
            return;
        }
        Thread t = new Thread(this::runSafely, "enrichment-startup-ingest");
        t.setDaemon(true);
        t.start();
    }

    @Scheduled(fixedDelayString = "${app.enrichment.interval-ms:21600000}",
            initialDelayString = "${app.enrichment.interval-ms:21600000}")
    public void poll() {
        runSafely();
    }

    private void runSafely() {
        try {
            ingestion.ingestAll();
        } catch (Exception e) {
            log.error("Enrichment run failed", e);
        }
    }
}
