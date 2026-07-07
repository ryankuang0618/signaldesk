package com.signaldesk.ingestion.edgar;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Talks to SEC EDGAR. Sends the required User-Agent and throttles requests to stay
 * well under SEC's 10 req/s guidance.
 */
@Component
public class EdgarClient {

    private final RestClient http;
    private final long delayMs;

    public EdgarClient(RestClient.Builder builder,
                       @Value("${app.edgar.user-agent}") String userAgent,
                       @Value("${app.edgar.request-delay-ms:130}") long delayMs) {
        this.http = builder.defaultHeader(HttpHeaders.USER_AGENT, userAgent).build();
        this.delayMs = delayMs;
    }

    /** Most recent Form 4 filings for an issuer, newest first, capped at {@code limit}. */
    public List<Form4Ref> fetchRecentForm4(String cik10, int limit) {
        throttle();
        JsonNode root = http.get()
                .uri("https://data.sec.gov/submissions/CIK{cik}.json", cik10)
                .retrieve()
                .body(JsonNode.class);

        List<Form4Ref> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        JsonNode recent = root.path("filings").path("recent");
        JsonNode forms = recent.path("form");
        JsonNode accs = recent.path("accessionNumber");
        JsonNode dates = recent.path("filingDate");
        JsonNode docs = recent.path("primaryDocument");

        for (int i = 0; i < forms.size() && out.size() < limit; i++) {
            if ("4".equals(forms.get(i).asText())) {
                out.add(new Form4Ref(
                        accs.get(i).asText(),
                        LocalDate.parse(dates.get(i).asText()),
                        docs.get(i).asText()));
            }
        }
        return out;
    }

    /** Raw Form 4 XML for a filing. Strips the {@code xslF345X0N/} viewer prefix to hit the source XML. */
    public String fetchForm4Xml(String cik10, Form4Ref ref) {
        throttle();
        String cikInt = String.valueOf(Long.parseLong(cik10));          // drop leading zeros for the archive path
        String accNoDashes = ref.accessionNumber().replace("-", "");
        String doc = ref.primaryDocument();
        int slash = doc.lastIndexOf('/');
        if (slash >= 0) {
            doc = doc.substring(slash + 1);                              // xslF345X06/form4.xml -> form4.xml
        }
        return http.get()
                .uri("https://www.sec.gov/Archives/edgar/data/{cik}/{acc}/{doc}", cikInt, accNoDashes, doc)
                .retrieve()
                .body(String.class);
    }

    private void throttle() {
        try {
            TimeUnit.MILLISECONDS.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
