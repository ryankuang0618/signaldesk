package com.signaldesk.ingestion.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.concurrent.TimeUnit;

/** Finnhub context endpoints that work on the free tier: recommendation, earnings, metrics. */
@Component
public class FinnhubEnrichmentClient {

    private final RestClient http;
    private final String baseUrl;
    private final String apiKey;
    private final long throttleMs;

    public FinnhubEnrichmentClient(RestClient.Builder builder,
                                   @Value("${app.news.base-url:https://finnhub.io/api/v1}") String baseUrl,
                                   @Value("${app.news.api-key:}") String apiKey,
                                   @Value("${app.enrichment.throttle-ms:1100}") long throttleMs) {
        this.http = builder.build();
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.throttleMs = throttleMs;
    }

    public boolean hasKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Analyst recommendation trends (array, newest period first). */
    public JsonNode recommendation(String symbol) {
        return get("/stock/recommendation?symbol={s}&token={k}", symbol);
    }

    /** Historical quarterly EPS surprises (array, newest first). */
    public JsonNode earnings(String symbol) {
        return get("/stock/earnings?symbol={s}&token={k}", symbol);
    }

    /** Basic financials — the {@code metric} object holds 52-week range, P/E, etc. */
    public JsonNode metric(String symbol) {
        throttle();
        return http.get().uri(baseUrl + "/stock/metric?symbol={s}&metric=all&token={k}", symbol, apiKey)
                .retrieve().body(JsonNode.class);
    }

    private JsonNode get(String pathTemplate, String symbol) {
        throttle();
        return http.get().uri(baseUrl + pathTemplate, symbol, apiKey).retrieve().body(JsonNode.class);
    }

    private void throttle() {
        try {
            TimeUnit.MILLISECONDS.sleep(throttleMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
