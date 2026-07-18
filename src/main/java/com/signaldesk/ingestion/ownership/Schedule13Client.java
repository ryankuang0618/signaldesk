package com.signaldesk.ingestion.ownership;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Schedule 13D/13G filings from a fund's EDGAR submissions and resolves each filing's SUBJECT
 * company (the stake target) to a ticker. 13D/13G are filed by the acquirer, so we look them up under
 * each tracked fund's CIK — "what >5% stakes did this notable investor just disclose?"
 */
@Component
public class Schedule13Client {

    private static final Logger log = LoggerFactory.getLogger(Schedule13Client.class);
    private static final Pattern SUBJECT_NAME = Pattern.compile("COMPANY CONFORMED NAME:\\s*(.+)");
    private static final Pattern SUBJECT_CIK = Pattern.compile("CENTRAL INDEX KEY:\\s*(\\d+)");

    /** Resolved subject: the stake target's ticker (may be null) and company name. */
    public record Subject(String ticker, String companyName) {
    }

    private final RestClient http;
    private final long delayMs;

    public Schedule13Client(RestClient.Builder builder,
                            @Value("${app.edgar.user-agent}") String userAgent,
                            @Value("${app.edgar.request-delay-ms:130}") long delayMs) {
        this.http = builder.defaultHeader(HttpHeaders.USER_AGENT, userAgent).build();
        this.delayMs = delayMs;
    }

    /** Recent 13D/13G filings by a fund CIK, newest first, capped at {@code limit}. */
    public List<Schedule13Ref> fetchRecent(String cik10, int limit) {
        throttle();
        JsonNode root = http.get()
                .uri("https://data.sec.gov/submissions/CIK{cik}.json", cik10)
                .retrieve()
                .body(JsonNode.class);

        List<Schedule13Ref> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        JsonNode r = root.path("filings").path("recent");
        JsonNode forms = r.path("form");
        JsonNode accs = r.path("accessionNumber");
        JsonNode dates = r.path("filingDate");
        for (int i = 0; i < forms.size() && out.size() < limit; i++) {
            String form = forms.get(i).asText();
            if (form.startsWith("SC 13D") || form.startsWith("SC 13G")) {
                out.add(new Schedule13Ref(accs.get(i).asText(), LocalDate.parse(dates.get(i).asText()), form));
            }
        }
        return out;
    }

    /**
     * Resolve a filing's SUBJECT company by reading its SGML header, then map that company's CIK to a
     * ticker via its own submissions feed. Returns null if the filing can't be fetched/parsed.
     */
    public Subject resolveSubject(String fundCik10, Schedule13Ref ref) {
        try {
            String cikInt = String.valueOf(Long.parseLong(fundCik10));      // archive path drops leading zeros
            String accNoDashes = ref.accessionNumber().replace("-", "");
            throttle();
            String txt = http.get()
                    .uri("https://www.sec.gov/Archives/edgar/data/{cik}/{acc}/{full}.txt",
                            cikInt, accNoDashes, ref.accessionNumber())
                    .retrieve()
                    .body(String.class);
            if (txt == null) {
                return null;
            }
            int idx = txt.indexOf("SUBJECT COMPANY");
            if (idx < 0) {
                return null;
            }
            String header = txt.substring(idx, Math.min(txt.length(), idx + 2000));
            Matcher nm = SUBJECT_NAME.matcher(header);
            Matcher cm = SUBJECT_CIK.matcher(header);
            String name = nm.find() ? nm.group(1).trim() : null;
            String subjectCik = cm.find() ? cm.group(1).trim() : null;
            return new Subject(subjectCik == null ? null : tickerForCik(subjectCik), name);
        } catch (Exception e) {
            log.debug("resolveSubject failed for {}: {}", ref.accessionNumber(), e.getMessage());
            return null;
        }
    }

    private String tickerForCik(String cik) {
        try {
            String padded = String.format("%010d", Long.parseLong(cik));
            throttle();
            JsonNode root = http.get()
                    .uri("https://data.sec.gov/submissions/CIK{cik}.json", padded)
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode tickers = root == null ? null : root.path("tickers");
            return (tickers != null && tickers.isArray() && !tickers.isEmpty()) ? tickers.get(0).asText() : null;
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
