package com.signaldesk.ingestion.congress;

import com.signaldesk.web.ws.LiveUpdatePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs Congress ingestion once shortly after startup, then on a fixed schedule. */
@Component
public class CongressPoller {

    private static final Logger log = LoggerFactory.getLogger(CongressPoller.class);

    private final CongressIngestionService ingestion;
    private final LiveUpdatePublisher live;
    private final boolean runOnStartup;

    public CongressPoller(CongressIngestionService ingestion,
                          LiveUpdatePublisher live,
                          @Value("${app.congress.run-on-startup:true}") boolean runOnStartup) {
        this.ingestion = ingestion;
        this.live = live;
        this.runOnStartup = runOnStartup;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!runOnStartup) {
            return;
        }
        Thread t = new Thread(this::runSafely, "congress-startup-ingest");
        t.setDaemon(true);
        t.start();
    }

    @Scheduled(fixedDelayString = "${app.congress.interval-ms:1800000}",
            initialDelayString = "${app.congress.interval-ms:1800000}")
    public void poll() {
        runSafely();
    }

    private void runSafely() {
        try {
            live.publish("CONGRESS", ingestion.ingestAll());
        } catch (Exception e) {
            log.error("Congress ingestion run failed", e);
        }
    }
}
