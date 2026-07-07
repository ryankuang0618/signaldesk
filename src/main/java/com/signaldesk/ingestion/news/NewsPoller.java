package com.signaldesk.ingestion.news;

import com.signaldesk.web.ws.LiveUpdatePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs news ingestion once after startup, then on a fixed schedule. */
@Component
public class NewsPoller {

    private static final Logger log = LoggerFactory.getLogger(NewsPoller.class);

    private final NewsIngestionService ingestion;
    private final LiveUpdatePublisher live;
    private final boolean runOnStartup;

    public NewsPoller(NewsIngestionService ingestion,
                      LiveUpdatePublisher live,
                      @Value("${app.news.run-on-startup:true}") boolean runOnStartup) {
        this.ingestion = ingestion;
        this.live = live;
        this.runOnStartup = runOnStartup;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!runOnStartup) {
            return;
        }
        Thread t = new Thread(this::runSafely, "news-startup-ingest");
        t.setDaemon(true);
        t.start();
    }

    @Scheduled(fixedDelayString = "${app.news.interval-ms:3600000}",
            initialDelayString = "${app.news.interval-ms:3600000}")
    public void poll() {
        runSafely();
    }

    private void runSafely() {
        try {
            live.publish("NEWS", ingestion.ingestAll());
        } catch (Exception e) {
            log.error("News ingestion run failed", e);
        }
    }
}
