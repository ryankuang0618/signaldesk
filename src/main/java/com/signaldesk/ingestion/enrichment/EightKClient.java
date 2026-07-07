package com.signaldesk.ingestion.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Fetches recent 8-K (material event) filings for an issuer from SEC EDGAR. */
@Component
public class EightKClient {

    /** One 8-K filing: accession, filing date, and the reported item codes (e.g. 2.02, 5.02). */
    public record EightKRef(String accessionNumber, LocalDate filingDate, String items) {
    }

    private final RestClient http;
    private final long delayMs;

    public EightKClient(RestClient.Builder builder,
                        @Value("${app.edgar.user-agent}") String userAgent,
                        @Value("${app.edgar.request-delay-ms:130}") long delayMs) {
        this.http = builder.defaultHeader(HttpHeaders.USER_AGENT, userAgent).build();
        this.delayMs = delayMs;
    }

    public List<EightKRef> fetchRecent(String cik10, int limit) {
        throttle();
        JsonNode root = http.get()
                .uri("https://data.sec.gov/submissions/CIK{cik}.json", cik10)
                .retrieve()
                .body(JsonNode.class);

        List<EightKRef> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        JsonNode r = root.path("filings").path("recent");
        JsonNode forms = r.path("form");
        JsonNode accs = r.path("accessionNumber");
        JsonNode filed = r.path("filingDate");
        JsonNode items = r.path("items");

        for (int i = 0; i < forms.size() && out.size() < limit; i++) {
            if ("8-K".equals(forms.get(i).asText())) {
                out.add(new EightKRef(
                        accs.get(i).asText(),
                        parseDate(filed.get(i).asText()),
                        items.get(i).asText("")));
            }
        }
        return out;
    }

    private static LocalDate parseDate(String raw) {
        try {
            return (raw == null || raw.isBlank()) ? null : LocalDate.parse(raw.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private void throttle() {
        try {
            TimeUnit.MILLISECONDS.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
