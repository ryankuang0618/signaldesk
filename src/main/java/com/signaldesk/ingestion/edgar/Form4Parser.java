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
import java.math.RoundingMode;
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
            String rel = "/ownershipDocument/reportingOwner/reportingOwnerRelationship/";
            String title = text(xp, doc, rel + "officerTitle");
            boolean isOfficer = boolFlag(text(xp, doc, rel + "isOfficer"));
            boolean isDirector = boolFlag(text(xp, doc, rel + "isDirector"));
            boolean isTenPercentOwner = boolFlag(text(xp, doc, rel + "isTenPercentOwner"));

            if (symbol == null || symbol.isBlank()) {
                return Optional.empty();
            }

            NodeList txns = (NodeList) xp.evaluate(
                    "/ownershipDocument/nonDerivativeTable/nonDerivativeTransaction",
                    doc, XPathConstants.NODESET);

            BigDecimal openMarketNet = BigDecimal.ZERO;
            BigDecimal totalNet = BigDecimal.ZERO;
            BigDecimal dollarValue = BigDecimal.ZERO;   // sum over open-market txns: |shares| × price
            BigDecimal maxAbs = BigDecimal.ZERO;        // tracks the largest transaction in the filing
            BigDecimal primaryPost = null;              // shares held after that largest transaction
            String primaryCode = null;
            boolean openMarket = false;
            LocalDate latest = null;

            for (int i = 0; i < txns.getLength(); i++) {
                Node t = txns.item(i);
                String code = text(xp, t, "transactionCoding/transactionCode");
                String sharesRaw = text(xp, t, "transactionAmounts/transactionShares/value");
                String ad = text(xp, t, "transactionAmounts/transactionAcquiredDisposedCode/value");
                String dateRaw = text(xp, t, "transactionDate/value");
                BigDecimal price = parseDecimal(text(xp, t, "transactionAmounts/transactionPricePerShare/value"));
                BigDecimal post = parseDecimal(text(xp, t, "postTransactionAmounts/sharesOwnedFollowingTransaction/value"));

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
                    if (price != null) {
                        dollarValue = dollarValue.add(shares.abs().multiply(price));
                    }
                }
                // The largest transaction represents the filing's primary code and holdings context.
                BigDecimal absShares = shares.abs();
                if (absShares.compareTo(maxAbs) > 0) {
                    maxAbs = absShares;
                    primaryCode = code == null ? null : code.trim().toUpperCase();
                    primaryPost = post;
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

            BigDecimal signalNet = openMarket ? openMarketNet : totalNet;
            BigDecimal pctOfHoldings = (primaryPost != null && primaryPost.signum() > 0)
                    ? signalNet.abs().divide(primaryPost, 4, RoundingMode.HALF_UP)
                    : null;
            BigDecimal dv = (openMarket && dollarValue.signum() > 0) ? dollarValue : null;

            return Optional.of(new Form4Result(symbol.trim(), owner, title, latest, openMarket,
                    openMarketNet, totalNet, isOfficer, isDirector, isTenPercentOwner,
                    primaryCode, dv, pctOfHoldings));
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

    private static BigDecimal parseDecimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Form 4 boolean flags come as "1"/"0" or "true"/"false". */
    private static boolean boolFlag(String v) {
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }
}
