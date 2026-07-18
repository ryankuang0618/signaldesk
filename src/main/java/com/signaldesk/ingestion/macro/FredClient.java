package com.signaldesk.ingestion.macro;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.concurrent.TimeUnit;

/**
 * FRED (Federal Reserve Economic Data) — free macro indicators. Used to build a market-regime
 * backdrop (rates, yield curve, VIX, inflation) the AI weighs its stock-level read against. Needs a
 * free API key (FRED_API_KEY); the feature is skipped gracefully without one.
 */
@Component
public class FredClient {

    private static final Logger log = LoggerFactory.getLogger(FredClient.class);

    private final RestClient http;
    private final String baseUrl;
    private final String apiKey;
    private final long throttleMs;

    public FredClient(RestClient.Builder builder,
                      @Value("${app.fred.base-url:https://api.stlouisfed.org/fred}") String baseUrl,
                      @Value("${app.fred.api-key:}") String apiKey,
                      @Value("${app.fred.throttle-ms:200}") long throttleMs) {
        this.http = builder.build();
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.throttleMs = throttleMs;
    }

    public boolean hasKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Latest numeric value of a series (most recent non-missing observation), or null. */
    public Double latest(String seriesId) {
        return latest(seriesId, null);
    }

    /**
     * Latest value of a series with an optional FRED units transform (e.g. {@code pc1} = percent
     * change from a year ago). Returns the most recent non-missing observation, or null on any error.
     */
    public Double latest(String seriesId, String units) {
        throttle();
        try {
            StringBuilder url = new StringBuilder(baseUrl)
                    .append("/series/observations?series_id=").append(seriesId)
                    .append("&api_key=").append(apiKey)
                    .append("&file_type=json&sort_order=desc&limit=10");
            if (units != null) {
                url.append("&units=").append(units);
            }
            JsonNode body = http.get().uri(url.toString()).retrieve().body(JsonNode.class);
            JsonNode obs = body == null ? null : body.path("observations");
            if (obs == null || !obs.isArray()) {
                return null;
            }
            // FRED marks missing values with "."; take the most recent parseable one.
            for (JsonNode o : obs) {
                String v = o.path("value").asText(".");
                if (!".".equals(v) && !v.isBlank()) {
                    try {
                        return Double.parseDouble(v);
                    } catch (NumberFormatException ignore) {
                        // keep scanning
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.debug("FRED fetch failed for {}: {}", seriesId, e.getMessage());
            return null;
        }
    }

    private void throttle() {
        try {
            TimeUnit.MILLISECONDS.sleep(throttleMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
