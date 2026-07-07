package com.signaldesk.ingestion.openfigi;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Maps CUSIPs to US tickers via OpenFIGI. Works without an API key (lower rate limits);
 * set {@code app.openfigi.api-key} to raise them.
 */
@Component
public class OpenFigiClient {

    private static final Logger log = LoggerFactory.getLogger(OpenFigiClient.class);

    private final RestClient http;
    private final String apiKey;
    private final int batchSize;
    private final long throttleMs;

    public OpenFigiClient(RestClient.Builder builder,
                          @Value("${app.openfigi.base-url:https://api.openfigi.com/v3/mapping}") String baseUrl,
                          @Value("${app.openfigi.api-key:}") String apiKey,
                          @Value("${app.openfigi.batch-size:10}") int batchSize,
                          @Value("${app.openfigi.throttle-ms:300}") long throttleMs) {
        this.http = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.batchSize = batchSize;
        this.throttleMs = throttleMs;
    }

    /** Resolve a batch of CUSIPs. Returns cusip → ticker for those found (missing ones are omitted). */
    public Map<String, String> mapCusips(List<String> cusips) {
        Map<String, String> result = new HashMap<>();
        for (int start = 0; start < cusips.size(); start += batchSize) {
            List<String> batch = cusips.subList(start, Math.min(start + batchSize, cusips.size()));
            try {
                resolveBatch(batch, result);
            } catch (Exception e) {
                log.warn("OpenFIGI batch failed ({} cusips): {}", batch.size(), e.getMessage());
            }
            throttle();
        }
        return result;
    }

    private void resolveBatch(List<String> batch, Map<String, String> out) {
        List<Map<String, String>> jobs = new ArrayList<>();
        for (String cusip : batch) {
            jobs.add(Map.of("idType", "ID_CUSIP", "idValue", cusip, "exchCode", "US"));
        }
        RestClient.RequestBodySpec req = http.post().contentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) {
            req = req.header("X-OPENFIGI-APIKEY", apiKey);
        }
        JsonNode resp = req.body(jobs).retrieve().body(JsonNode.class);
        if (resp == null || !resp.isArray()) {
            return;
        }
        // Response array is positionally aligned with the request jobs.
        for (int i = 0; i < resp.size() && i < batch.size(); i++) {
            JsonNode data = resp.get(i).path("data");
            if (data.isArray() && data.size() > 0) {
                String ticker = data.get(0).path("ticker").asText(null);
                if (ticker != null && !ticker.isBlank()) {
                    out.put(batch.get(i), ticker.trim().toUpperCase());
                }
            }
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
