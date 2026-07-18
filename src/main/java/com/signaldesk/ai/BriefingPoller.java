package com.signaldesk.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Generates the day's briefings on a schedule so the LINE bot can answer instantly from stored
 * results (no Claude call on the request path). Runs once each morning by default; the actual
 * generation is gated inside {@link BriefingService#startJob()} (skips without an API key or when
 * the feature is disabled), so this poller stays a thin trigger.
 */
@Component
public class BriefingPoller {

    private static final Logger log = LoggerFactory.getLogger(BriefingPoller.class);

    private final BriefingService service;
    private final boolean autoRun;

    public BriefingPoller(BriefingService service,
                          @Value("${app.briefing.auto-run:true}") boolean autoRun) {
        this.service = service;
        this.autoRun = autoRun;
    }

    /**
     * Default: 08:00 daily in the configured zone. Override the time with {@code app.briefing.cron}
     * and the zone with {@code app.briefing.timezone} (e.g. Asia/Taipei, America/New_York).
     */
    @Scheduled(cron = "${app.briefing.cron:0 0 8 * * *}", zone = "${app.briefing.timezone:UTC}")
    public void daily() {
        if (!autoRun) {
            return;
        }
        try {
            BriefingJob job = service.startJob();
            log.info("Daily briefing run triggered — job {} ({})", job.getId(), job.getStatus());
        } catch (Exception e) {
            log.error("Daily briefing run failed to start", e);
        }
    }
}
