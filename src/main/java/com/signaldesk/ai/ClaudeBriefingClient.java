package com.signaldesk.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper over the Anthropic SDK for the briefing feature. The client is built lazily so the
 * app boots fine without an ANTHROPIC_API_KEY — the briefing simply stays disabled until one is set.
 */
@Component
public class ClaudeBriefingClient {

    private final String model;
    private final long maxTokens;
    private volatile AnthropicClient client;

    public ClaudeBriefingClient(@Value("${app.briefing.model:claude-sonnet-4-6}") String model,
                                @Value("${app.briefing.max-tokens:1500}") long maxTokens) {
        this.model = model;
        this.maxTokens = maxTokens;
    }

    public boolean hasKey() {
        String key = System.getenv("ANTHROPIC_API_KEY");
        return key != null && !key.isBlank();
    }

    public String model() {
        return model;
    }

    /** Send one system+user prompt and return the concatenated text of the response. */
    public String complete(String system, String user) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .system(system)          // top-level system prompt (not a messages entry)
                .addUserMessage(user)
                .build();

        Message message = client().messages().create(params);

        StringBuilder sb = new StringBuilder();
        message.content().forEach(block -> {
            if (block.isText()) {
                sb.append(block.asText().text());
            }
        });
        return sb.toString();
    }

    private AnthropicClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    client = AnthropicOkHttpClient.fromEnv();   // reads ANTHROPIC_API_KEY
                }
            }
        }
        return client;
    }
}
