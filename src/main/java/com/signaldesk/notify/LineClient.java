package com.signaldesk.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Talks to the LINE Messaging API. Outbound: {@link #push} (proactive, quota-limited) and
 * {@link #reply} (answering an inbound webhook via its reply token — free and unlimited, so it's
 * the bot's default). Inbound: {@link #verifySignature} authenticates webhook calls from LINE.
 */
@Component
public class LineClient {

    private static final Logger log = LoggerFactory.getLogger(LineClient.class);
    private static final String HMAC_SHA256 = "HmacSHA256";
    /** LINE rejects text messages longer than 5000 chars; clamp below that so a reply never silently fails. */
    private static final int MAX_TEXT = 4900;

    private final RestClient http;
    private final String pushUrl;
    private final String replyUrl;
    private final String channelToken;
    private final String channelSecret;
    private final String userId;

    public LineClient(RestClient.Builder builder,
                      @Value("${app.line.push-url:https://api.line.me/v2/bot/message/push}") String pushUrl,
                      @Value("${app.line.reply-url:https://api.line.me/v2/bot/message/reply}") String replyUrl,
                      @Value("${app.line.channel-token:}") String channelToken,
                      @Value("${app.line.channel-secret:}") String channelSecret,
                      @Value("${app.line.user-id:}") String userId) {
        this.http = builder.build();
        this.pushUrl = pushUrl;
        this.replyUrl = replyUrl;
        this.channelToken = channelToken;
        this.channelSecret = channelSecret;
        this.userId = userId;
    }

    /** Whether outbound push (proactive alerts) is configured — needs a token and a target user. */
    public boolean isConfigured() {
        return isSet(channelToken) && isSet(userId);
    }

    /** Whether the inbound webhook can authenticate LINE's requests — needs a token to reply and a secret to verify. */
    public boolean isWebhookConfigured() {
        return isSet(channelToken) && isSet(channelSecret);
    }

    /**
     * Verify LINE's {@code X-Line-Signature}: Base64(HMAC-SHA256(channelSecret, rawBody)). Rejects
     * everything if no secret is configured, so an unconfigured bot can't be driven by forged calls.
     */
    public boolean verifySignature(String rawBody, String signature) {
        if (!isSet(channelSecret) || signature == null || rawBody == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(channelSecret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] expected = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            byte[] provided = Base64.getDecoder().decode(signature);
            return MessageDigest.isEqual(expected, provided);   // constant-time compare
        } catch (Exception e) {
            log.warn("LINE signature verification error: {}", e.getMessage());
            return false;
        }
    }

    /** Reply to an inbound message using its reply token (free, unlimited). Returns true on success. */
    public boolean reply(String replyToken, String text) {
        if (!isSet(channelToken) || !isSet(replyToken)) {
            return false;
        }
        return send(replyUrl, Map.of(
                "replyToken", replyToken,
                "messages", List.of(Map.of("type", "text", "text", clamp(text)))));
    }

    /** Push a text message to the configured user (proactive; counts against the LINE push quota). */
    public boolean push(String text) {
        if (!isConfigured()) {
            return false;
        }
        return send(pushUrl, Map.of(
                "to", userId,
                "messages", List.of(Map.of("type", "text", "text", clamp(text)))));
    }

    /** Keep a message under LINE's hard 5000-char limit. */
    private static String clamp(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT - 1) + "…";
    }

    private boolean send(String url, Map<String, Object> body) {
        try {
            http.post()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + channelToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("LINE call to {} failed: {}", url, e.getMessage());
            return false;
        }
    }

    private static boolean isSet(String s) {
        return s != null && !s.isBlank();
    }
}
