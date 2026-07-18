package com.signaldesk.ingestion.market;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

/**
 * Alpaca Market Data — historical daily bars. Free with an Alpaca account (the IEX feed); no trading
 * permission needed, just market-data keys. Bars power the momentum / relative-strength / liquidity /
 * volatility features that Finnhub's free tier can't provide (it has no historical candles).
 */
@Component
public class AlpacaMarketClient {

    private final RestClient http;
    private final String baseUrl;
    private final String keyId;
    private final String secretKey;
    private final String feed;
    private final long throttleMs;

    public AlpacaMarketClient(RestClient.Builder builder,
                              @Value("${app.alpaca.base-url:https://data.alpaca.markets}") String baseUrl,
                              @Value("${app.alpaca.key-id:}") String keyId,
                              @Value("${app.alpaca.secret-key:}") String secretKey,
                              @Value("${app.alpaca.feed:iex}") String feed,
                              @Value("${app.alpaca.throttle-ms:350}") long throttleMs) {
        this.http = builder.build();
        this.baseUrl = baseUrl;
        this.keyId = keyId;
        this.secretKey = secretKey;
        this.feed = feed;
        this.throttleMs = throttleMs;
    }

    public boolean hasKeys() {
        return keyId != null && !keyId.isBlank() && secretKey != null && !secretKey.isBlank();
    }

    /**
     * Daily bars for a symbol over the last {@code lookbackDays} calendar days, oldest first. Returns
     * the {@code bars} array node (may be empty/missing for unknown or thinly-traded symbols).
     */
    public JsonNode dailyBars(String symbol, int lookbackDays) {
        throttle();
        String start = LocalDate.now().minusDays(lookbackDays).toString();
        JsonNode body = http.get()
                .uri(baseUrl + "/v2/stocks/{sym}/bars?timeframe=1Day&start={start}&limit=1000&adjustment=all&feed={feed}",
                        symbol, start, feed)
                .header("APCA-API-KEY-ID", keyId)
                .header("APCA-API-SECRET-KEY", secretKey)
                .retrieve()
                .body(JsonNode.class);
        return body == null ? null : body.path("bars");
    }

    private void throttle() {
        try {
            TimeUnit.MILLISECONDS.sleep(throttleMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
