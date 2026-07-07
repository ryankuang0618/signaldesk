package com.signaldesk.web.ws;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/** Broadcasts ingestion updates to WebSocket subscribers on /topic/updates. */
@Component
public class LiveUpdatePublisher {

    public static final String TOPIC = "/topic/updates";

    private final SimpMessagingTemplate messaging;

    public LiveUpdatePublisher(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    /**
     * Announce the result of an ingestion run.
     *
     * @param source   which feed ran (INSIDER_FORM4, CONGRESS, THIRTEEN_F, NEWS, MANUAL)
     * @param newCount how many new items it stored
     */
    public void publish(String source, int newCount) {
        messaging.convertAndSend(TOPIC, Map.of(
                "source", source,
                "newCount", newCount,
                "at", Instant.now().toString()));
    }
}
