package com.signaldesk.ingestion.edgar;

import com.signaldesk.web.ws.LiveUpdatePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives Form 4 ingestion: once shortly after startup, then on a fixed schedule.
 * The startup run happens on its own thread so it never blocks the web server.
 */
@Component
public class InsiderPoller {

    private static final Logger log = LoggerFactory.getLogger(InsiderPoller.class);

    private final Form4IngestionService ingestion;
    private final LiveUpdatePublisher live;
    private final boolean enabled;
    private final boolean runOnStartup;

    public InsiderPoller(Form4IngestionService ingestion,
                         LiveUpdatePublisher live,
                         @Value("${app.ingestion.enabled:true}") boolean enabled,
                         @Value("${app.ingestion.run-on-startup:true}") boolean runOnStartup) {
        this.ingestion = ingestion;
        this.live = live;
        this.enabled = enabled;
        this.runOnStartup = runOnStartup;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!enabled || !runOnStartup) {
            return;
        }
        Thread t = new Thread(this::runSafely, "insider-startup-ingest");
        t.setDaemon(true);
        t.start();
    }

    @Scheduled(fixedDelayString = "${app.ingestion.interval-ms:1800000}",
            initialDelayString = "${app.ingestion.interval-ms:1800000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        runSafely();
    }

    private void runSafely() {
        try {
            live.publish("INSIDER_FORM4", ingestion.ingestAll());
        } catch (Exception e) {
            log.error("Form 4 ingestion run failed", e);
        }
    }
}
