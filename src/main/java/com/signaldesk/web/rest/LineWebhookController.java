package com.signaldesk.web.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.signaldesk.notify.LineBotService;
import com.signaldesk.notify.LineClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Inbound LINE webhook. LINE POSTs message events here; we verify the signature, then reply to each
 * text message with the bot's answer using its reply token. This endpoint must stay outside HTTP
 * basic auth (LINE can't send auth headers) — the {@code X-Line-Signature} check is its auth.
 */
@RestController
@RequestMapping("/api/line")
public class LineWebhookController {

    private static final Logger log = LoggerFactory.getLogger(LineWebhookController.class);

    private final LineClient line;
    private final LineBotService bot;
    private final ObjectMapper mapper;

    public LineWebhookController(LineClient line, LineBotService bot, ObjectMapper mapper) {
        this.line = line;
        this.bot = bot;
        this.mapper = mapper;
    }

    /** Lets you (and the LINE console) confirm the route is live without sending an event. */
    @GetMapping("/webhook")
    public Map<String, Object> status() {
        return Map.of("ok", true, "webhookConfigured", line.isWebhookConfigured());
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> webhook(@RequestBody(required = false) String body,
                                        @RequestHeader(value = "X-Line-Signature", required = false) String signature) {
        String raw = body == null ? "" : body;
        if (!line.verifySignature(raw, signature)) {
            log.warn("Rejected LINE webhook — bad or missing signature");
            return ResponseEntity.status(401).build();
        }
        try {
            JsonNode events = mapper.readTree(raw).path("events");
            for (JsonNode event : events) {
                handle(event);
            }
        } catch (Exception e) {
            // Always 200 once the signature is valid: a parse/handler error shouldn't make LINE retry.
            log.warn("LINE webhook handling error: {}", e.getMessage());
        }
        return ResponseEntity.ok().build();
    }

    private void handle(JsonNode event) {
        if (!"message".equals(event.path("type").asText())
                || !"text".equals(event.path("message").path("type").asText())) {
            return;   // ignore stickers, follows, the console's verify ping, etc.
        }
        String replyToken = event.path("replyToken").asText(null);
        String text = event.path("message").path("text").asText("");
        line.reply(replyToken, bot.reply(text));
    }
}
