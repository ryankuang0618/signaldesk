package com.signaldesk.ingestion.ownership;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Ingests tracked funds' 13D/13G filings once after startup, then on a slow schedule. */
@Component
public class Schedule13Poller {

    private static final Logger log = LoggerFactory.getLogger(Schedule13Poller.class);

    private final Schedule13IngestionService ingestion;
    private final boolean runOnStartup;

    public Schedule13Poller(Schedule13IngestionService ingestion,
                            @Value("${app.schedule13.run-on-startup:true}") boolean runOnStartup) {
        this.ingestion = ingestion;
        this.runOnStartup = runOnStartup;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!runOnStartup) {
            return;
        }
        Thread t = new Thread(this::runSafely, "schedule13-startup-ingest");
        t.setDaemon(true);
        t.start();
    }

    @Scheduled(fixedDelayString = "${app.schedule13.interval-ms:21600000}",
            initialDelayString = "${app.schedule13.interval-ms:21600000}")
    public void poll() {
        runSafely();
    }

    private void runSafely() {
        try {
            ingestion.ingestAll();
        } catch (Exception e) {
            log.error("13D/13G run failed", e);
        }
    }
}
