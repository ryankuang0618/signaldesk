package com.signaldesk.ingestion.news;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One article from Finnhub's company-news endpoint. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NewsDto(
        long datetime,        // unix seconds
        String headline,
        String source,
        String summary,
        String url,
        String related,       // ticker(s)
        String category
) {
}
