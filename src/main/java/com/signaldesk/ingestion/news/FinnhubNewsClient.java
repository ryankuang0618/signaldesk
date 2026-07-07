package com.signaldesk.ingestion.news;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/** Fetches company news from Finnhub. Free tier includes company-news (60 calls/min). */
@Component
public class FinnhubNewsClient {

    private final RestClient http;
    private final String baseUrl;
    private final String apiKey;

    public FinnhubNewsClient(RestClient.Builder builder,
                             @Value("${app.news.base-url:https://finnhub.io/api/v1}") String baseUrl,
                             @Value("${app.news.api-key:}") String apiKey) {
        this.http = builder.build();
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public List<NewsDto> companyNews(String symbol, LocalDate from, LocalDate to) {
        NewsDto[] body = http.get()
                .uri(baseUrl + "/company-news?symbol={s}&from={from}&to={to}&token={key}",
                        symbol, from.toString(), to.toString(), apiKey)
                .retrieve()
                .body(NewsDto[].class);
        return body == null ? Collections.emptyList() : List.of(body);
    }
}
