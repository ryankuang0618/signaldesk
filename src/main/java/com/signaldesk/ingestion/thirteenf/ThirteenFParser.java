package com.signaldesk.ingestion.thirteenf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a 13F information table into aggregated {@link Holding}s.
 *
 * <p>The document is namespaced, so element lookups ignore the namespace. The same CUSIP can
 * appear multiple times (one row per manager/account); those are summed. Option positions
 * (rows carrying a {@code putCall}) are skipped — they aren't share holdings.
 */
@Component
public class ThirteenFParser {

    private static final Logger log = LoggerFactory.getLogger(ThirteenFParser.class);

    public List<Holding> parse(String xml) {
        Map<String, Holding> byCusip = new LinkedHashMap<>();
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setNamespaceAware(true);
            dbf.setExpandEntityReferences(false);
            Document doc = dbf.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));

            NodeList rows = doc.getElementsByTagNameNS("*", "infoTable");
            for (int i = 0; i < rows.getLength(); i++) {
                Element row = (Element) rows.item(i);
                if (first(row, "putCall") != null) {
                    continue;   // option position, not shares
                }
                String cusip = first(row, "cusip");
                if (cusip == null) {
                    continue;
                }
                cusip = cusip.trim().toUpperCase();
                String issuer = first(row, "nameOfIssuer");
                BigDecimal shares = num(first(row, "sshPrnamt"));
                BigDecimal value = num(first(row, "value"));

                Holding existing = byCusip.get(cusip);
                if (existing == null) {
                    byCusip.put(cusip, new Holding(cusip, issuer, shares, value));
                } else {
                    byCusip.put(cusip, new Holding(cusip,
                            existing.issuerName() != null ? existing.issuerName() : issuer,
                            existing.shares().add(shares),
                            existing.value().add(value)));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse 13F info table: {}", e.getMessage());
        }
        return new ArrayList<>(byCusip.values());
    }

    private static String first(Element row, String localName) {
        NodeList nl = row.getElementsByTagNameNS("*", localName);
        if (nl.getLength() == 0) {
            return null;
        }
        Node n = nl.item(0);
        String t = n.getTextContent();
        return (t == null || t.isBlank()) ? null : t.trim();
    }

    private static BigDecimal num(String raw) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
