package com.signaldesk.ingestion.congress;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;

/** Fetches recent Congress trades from Financial Modeling Prep's stable endpoints. */
@Component
public class CongressClient {

    private final RestClient http;
    private final String baseUrl;
    private final String apiKey;

    public CongressClient(RestClient.Builder builder,
                          @Value("${app.edgar.user-agent}") String userAgent,
                          @Value("${app.congress.base-url:https://financialmodelingprep.com/stable}") String baseUrl,
                          @Value("${app.congress.api-key:}") String apiKey) {
        this.http = builder.defaultHeader(HttpHeaders.USER_AGENT, userAgent).build();
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Latest trades for a chamber.
     *
     * @param chamber "senate-latest" or "house-latest"
     */
    public List<CongressTrade> fetchLatest(String chamber, int page, int limit) {
        CongressTrade[] body = http.get()
                .uri(baseUrl + "/{chamber}?page={page}&limit={limit}&apikey={key}", chamber, page, limit, apiKey)
                .retrieve()
                .body(CongressTrade[].class);
        return body == null ? Collections.emptyList() : List.of(body);
    }
}
