package com.signaldesk.ingestion.thirteenf;

import com.signaldesk.web.ws.LiveUpdatePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs 13F ingestion once after startup, then on a slow schedule (13F changes only quarterly). */
@Component
public class ThirteenFPoller {

    private static final Logger log = LoggerFactory.getLogger(ThirteenFPoller.class);

    private final ThirteenFIngestionService ingestion;
    private final LiveUpdatePublisher live;
    private final boolean runOnStartup;

    public ThirteenFPoller(ThirteenFIngestionService ingestion,
                           LiveUpdatePublisher live,
                           @Value("${app.thirteenf.run-on-startup:true}") boolean runOnStartup) {
        this.ingestion = ingestion;
        this.live = live;
        this.runOnStartup = runOnStartup;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!runOnStartup) {
            return;
        }
        Thread t = new Thread(this::runSafely, "thirteenf-startup-ingest");
        t.setDaemon(true);
        t.start();
    }

    @Scheduled(fixedDelayString = "${app.thirteenf.interval-ms:21600000}",
            initialDelayString = "${app.thirteenf.interval-ms:21600000}")
    public void poll() {
        runSafely();
    }

    private void runSafely() {
        try {
            live.publish("THIRTEEN_F", ingestion.ingestAll());
        } catch (Exception e) {
            log.error("13F ingestion run failed", e);
        }
    }
}
