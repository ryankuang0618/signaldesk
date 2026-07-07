package com.signaldesk.ingestion.edgar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Reduces a Form 4 XML document to a directional {@link Form4Result}.
 *
 * <p>Simplification: one signal per filing (net direction), not one per transaction.
 * Open-market purchases (code P) and sales (code S) are the high-signal moves; grants,
 * option exercises, and tax withholding are treated as low-signal context.
 */
@Component
public class Form4Parser {

    private static final Logger log = LoggerFactory.getLogger(Form4Parser.class);

    public Optional<Form4Result> parse(String xml) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            // XXE hardening — these documents never legitimately need DTDs or external entities.
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setExpandEntityReferences(false);
            Document doc = dbf.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));

            XPath xp = XPathFactory.newInstance().newXPath();

            String symbol = text(xp, doc, "/ownershipDocument/issuer/issuerTradingSymbol");
            String owner = text(xp, doc, "/ownershipDocument/reportingOwner/reportingOwnerId/rptOwnerName");
            String title = text(xp, doc, "/ownershipDocument/reportingOwner/reportingOwnerRelationship/officerTitle");

            if (symbol == null || symbol.isBlank()) {
                return Optional.empty();
            }

            NodeList txns = (NodeList) xp.evaluate(
                    "/ownershipDocument/nonDerivativeTable/nonDerivativeTransaction",
                    doc, XPathConstants.NODESET);

            BigDecimal openMarketNet = BigDecimal.ZERO;
            BigDecimal totalNet = BigDecimal.ZERO;
            boolean openMarket = false;
            LocalDate latest = null;

            for (int i = 0; i < txns.getLength(); i++) {
                Node t = txns.item(i);
                String code = text(xp, t, "transactionCoding/transactionCode");
                String sharesRaw = text(xp, t, "transactionAmounts/transactionShares/value");
                String ad = text(xp, t, "transactionAmounts/transactionAcquiredDisposedCode/value");
                String dateRaw = text(xp, t, "transactionDate/value");

                if (sharesRaw == null || sharesRaw.isBlank()) {
                    continue;
                }
                BigDecimal shares;
                try {
                    shares = new BigDecimal(sharesRaw.trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                // Acquired adds, disposed subtracts.
                BigDecimal signed = "D".equalsIgnoreCase(ad) ? shares.negate() : shares;
                totalNet = totalNet.add(signed);

                if ("P".equalsIgnoreCase(code) || "S".equalsIgnoreCase(code)) {
                    openMarket = true;
                    openMarketNet = openMarketNet.add(signed);
                }
                LocalDate d = parseDate(dateRaw);
                if (d != null && (latest == null || d.isAfter(latest))) {
                    latest = d;
                }
            }

            if (totalNet.signum() == 0 && !openMarket) {
                // No usable non-derivative transactions (e.g. derivative-only filing) — skip.
                return Optional.empty();
            }

            return Optional.of(new Form4Result(symbol.trim(), owner, title, latest, openMarket, openMarketNet, totalNet));
        } catch (Exception e) {
            log.warn("Failed to parse Form 4 XML: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static String text(XPath xp, Object ctx, String expr) throws Exception {
        String v = (String) xp.evaluate(expr, ctx, XPathConstants.STRING);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
