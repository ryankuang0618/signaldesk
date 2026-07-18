package com.signaldesk.web.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Records the result of an ingestion run. The dashboard (and its WebSocket live-push) was removed
 * when SignalDesk became a LINE-only bot, so this is now a lightweight log hook rather than a
 * broadcaster. Kept as a seam: every poller/service already calls {@link #publish}, so re-adding a
 * transport (WebSocket, SSE, a metrics counter) later is a one-file change.
 */
@Component
public class LiveUpdatePublisher {

    private static final Logger log = LoggerFactory.getLogger(LiveUpdatePublisher.class);

    /**
     * Note the result of an ingestion run.
     *
     * @param source   which feed ran (INSIDER_FORM4, CONGRESS, THIRTEEN_F, NEWS, BRIEFING, ...)
     * @param newCount how many new items it stored
     */
    public void publish(String source, int newCount) {
        log.debug("ingestion update: {} +{}", source, newCount);
    }
}
