package com.signaldesk.ingestion.macro;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Refreshes the macro regime once after startup, then on a slow schedule (macro moves slowly). */
@Component
public class MacroPoller {

    private static final Logger log = LoggerFactory.getLogger(MacroPoller.class);

    private final MacroService macro;
    private final boolean runOnStartup;

    public MacroPoller(MacroService macro,
                       @Value("${app.fred.run-on-startup:true}") boolean runOnStartup) {
        this.macro = macro;
        this.runOnStartup = runOnStartup;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!runOnStartup) {
            return;
        }
        Thread t = new Thread(this::runSafely, "macro-startup-refresh");
        t.setDaemon(true);
        t.start();
    }

    @Scheduled(fixedDelayString = "${app.fred.interval-ms:21600000}",
            initialDelayString = "${app.fred.interval-ms:21600000}")
    public void poll() {
        runSafely();
    }

    private void runSafely() {
        try {
            macro.refresh();
        } catch (Exception e) {
            log.error("Macro refresh failed", e);
        }
    }
}
