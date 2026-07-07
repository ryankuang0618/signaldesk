package com.signaldesk.ingestion.thirteenf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Fetches 13F-HR filings and their information-table XML from SEC EDGAR. */
@Component
public class ThirteenFClient {

    private final RestClient http;
    private final ObjectMapper mapper;
    private final long delayMs;

    public ThirteenFClient(RestClient.Builder builder,
                           ObjectMapper mapper,
                           @Value("${app.edgar.user-agent}") String userAgent,
                           @Value("${app.edgar.request-delay-ms:130}") long delayMs) {
        this.http = builder.defaultHeader(HttpHeaders.USER_AGENT, userAgent).build();
        this.mapper = mapper;
        this.delayMs = delayMs;
    }

    /** Most recent 13F-HR filings for a fund, newest first, capped at {@code limit}. */
    public List<Filing13FRef> fetchRecent(String cik10, int limit) {
        throttle();
        JsonNode root = http.get()
                .uri("https://data.sec.gov/submissions/CIK{cik}.json", cik10)
                .retrieve()
                .body(JsonNode.class);

        List<Filing13FRef> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        JsonNode r = root.path("filings").path("recent");
        JsonNode forms = r.path("form");
        JsonNode accs = r.path("accessionNumber");
        JsonNode filed = r.path("filingDate");
        JsonNode period = r.path("reportDate");

        for (int i = 0; i < forms.size() && out.size() < limit; i++) {
            if ("13F-HR".equals(forms.get(i).asText())) {
                out.add(new Filing13FRef(
                        accs.get(i).asText(),
                        parseDate(filed.get(i).asText()),
                        parseDate(period.get(i).asText())));
            }
        }
        return out;
    }

    /** Locate the information-table XML in a filing folder (the .xml that isn't primary_doc.xml). */
    public String findInfoTableUrl(String cik10, String accession) {
        throttle();
        String cikInt = String.valueOf(Long.parseLong(cik10));
        String accNoDashes = accession.replace("-", "");
        // www.sec.gov serves index.json as text/html, so read it as a String and parse it ourselves.
        String body = http.get()
                .uri("https://www.sec.gov/Archives/edgar/data/{cik}/{acc}/index.json", cikInt, accNoDashes)
                .retrieve()
                .body(String.class);
        JsonNode idx;
        try {
            idx = mapper.readTree(body);
        } catch (Exception e) {
            return null;
        }
        if (idx == null) {
            return null;
        }
        for (JsonNode item : idx.path("directory").path("item")) {
            String name = item.path("name").asText();
            if (name.endsWith(".xml")
                    && !name.equalsIgnoreCase("primary_doc.xml")
                    && !name.toLowerCase().endsWith("-index.xml")) {
                return "https://www.sec.gov/Archives/edgar/data/" + cikInt + "/" + accNoDashes + "/" + name;
            }
        }
        return null;
    }

    public String fetchXml(String url) {
        throttle();
        return http.get().uri(url).retrieve().body(String.class);
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
