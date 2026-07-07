package com.signaldesk.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/** Pushes text messages to a LINE user via the LINE Messaging API. */
@Component
public class LineClient {

    private static final Logger log = LoggerFactory.getLogger(LineClient.class);

    private final RestClient http;
    private final String pushUrl;
    private final String channelToken;
    private final String userId;

    public LineClient(RestClient.Builder builder,
                      @Value("${app.line.push-url:https://api.line.me/v2/bot/message/push}") String pushUrl,
                      @Value("${app.line.channel-token:}") String channelToken,
                      @Value("${app.line.user-id:}") String userId) {
        this.http = builder.build();
        this.pushUrl = pushUrl;
        this.channelToken = channelToken;
        this.userId = userId;
    }

    public boolean isConfigured() {
        return channelToken != null && !channelToken.isBlank()
                && userId != null && !userId.isBlank();
    }

    /** Push a text message to the configured user. Returns true on success. */
    public boolean push(String text) {
        if (!isConfigured()) {
            return false;
        }
        try {
            http.post()
                    .uri(pushUrl)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + channelToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "to", userId,
                            "messages", List.of(Map.of("type", "text", "text", text))))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("LINE push failed: {}", e.getMessage());
            return false;
        }
    }
}
