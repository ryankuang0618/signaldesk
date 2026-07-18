package com.signaldesk.portfolio;

import com.fasterxml.jackson.databind.JsonNode;
import com.signaldesk.domain.Briefing;
import com.signaldesk.domain.PortfolioPosition;
import com.signaldesk.domain.enums.PositionSource;
import com.signaldesk.domain.enums.Side;
import com.signaldesk.ingestion.enrichment.FinnhubEnrichmentClient;
import com.signaldesk.repository.BriefingRepository;
import com.signaldesk.repository.PortfolioPositionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Your personal (manually-entered) portfolio. Records what you own so the bot can answer "what do I
 * hold?" with live prices + P/L and cross-reference each position against today's AI signal — the
 * step that turns generic signals into advice about <em>your</em> stocks. Cost basis is per share.
 */
@Service
public class PortfolioService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioService.class);
    private static final PositionSource SOURCE = PositionSource.MANUAL;

    // "own NVDA 50 @ 120"  or  "own NVDA 50"  (also accepts add/buy, "at", a leading $, decimals)
    private static final Pattern OWN = Pattern.compile(
            "^(?:own|add|buy)\\s+\\$?([A-Za-z]{1,6})\\s+([0-9]+(?:\\.[0-9]+)?)"
                    + "(?:\\s*(?:@|at)\\s*\\$?([0-9]+(?:\\.[0-9]+)?))?\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REMOVE = Pattern.compile(
            "^(?:remove|drop|sell)\\s+\\$?([A-Za-z]{1,6})\\s*$", Pattern.CASE_INSENSITIVE);

    private final PortfolioPositionRepository positions;
    private final BriefingRepository briefings;
    private final FinnhubEnrichmentClient finnhub;

    public PortfolioService(PortfolioPositionRepository positions,
                            BriefingRepository briefings,
                            FinnhubEnrichmentClient finnhub) {
        this.positions = positions;
        this.briefings = briefings;
        this.finnhub = finnhub;
    }

    /** Record or update a holding from "own TICKER shares [@ price]". */
    public String own(String text) {
        Matcher m = OWN.matcher(text.trim());
        if (!m.matches()) {
            return "To record a holding:\n  own <TICKER> <shares> [@ <price>]\n\ne.g.  own NVDA 50 @ 120";
        }
        String ticker = m.group(1).toUpperCase(Locale.ROOT);
        BigDecimal shares = new BigDecimal(m.group(2));
        BigDecimal cost = m.group(3) == null ? null : new BigDecimal(m.group(3));

        PortfolioPosition p = positions.findBySourceAndTicker(SOURCE, ticker)
                .orElseGet(() -> {
                    PortfolioPosition np = new PortfolioPosition();
                    np.setTicker(ticker);
                    np.setSource(SOURCE);
                    return np;
                });
        p.setShares(shares);
        if (cost != null) {
            p.setCostBasis(cost);   // per-share; keep the previous basis if this update omits a price
        }
        positions.save(p);

        StringBuilder sb = new StringBuilder("✅ Recorded ").append(qty(shares)).append(" ").append(ticker);
        if (cost != null) {
            sb.append(" @ ").append(money(cost)).append(" (cost ").append(money(shares.multiply(cost))).append(")");
        }
        return sb.append(".\nSend 'holdings' to view your portfolio.").toString();
    }

    /** Remove a holding from "remove TICKER". */
    public String remove(String text) {
        Matcher m = REMOVE.matcher(text.trim());
        if (!m.matches()) {
            return "To remove a holding:\n  remove <TICKER>\n\ne.g.  remove NVDA";
        }
        String ticker = m.group(1).toUpperCase(Locale.ROOT);
        return positions.findBySourceAndTicker(SOURCE, ticker)
                .map(p -> {
                    positions.delete(p);
                    return "🗑️ Removed " + ticker + " from your holdings.";
                })
                .orElse("You don't have " + ticker + " recorded. Send 'holdings' to see what you do.");
    }

    /** The portfolio view: each position with live price, P/L, and today's signal if one exists. */
    public String holdings() {
        List<PortfolioPosition> held = positions.findBySource(SOURCE);
        if (held.isEmpty()) {
            return "No holdings recorded yet.\nAdd one:  own NVDA 50 @ 120";
        }
        Map<String, Briefing> todays = todaysBriefingsByTicker();
        boolean quotes = finnhub.hasKey();

        StringBuilder sb = new StringBuilder("💼 Your holdings");
        BigDecimal totalValue = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;

        for (PortfolioPosition p : held) {
            BigDecimal shares = p.getShares();
            BigDecimal cost = p.getCostBasis();
            BigDecimal price = quotes ? quotePrice(p.getTicker()) : null;

            sb.append("\n\n").append(p.getTicker()).append("  ").append(qty(shares)).append(" sh");
            if (cost != null) {
                sb.append(" @ ").append(money(cost));
            }
            if (price != null) {
                BigDecimal mv = shares.multiply(price);
                totalValue = totalValue.add(mv);
                sb.append("\n  now ").append(money(price)).append("  ·  value ").append(money(mv));
                if (cost != null) {
                    BigDecimal tc = shares.multiply(cost);
                    totalCost = totalCost.add(tc);
                    BigDecimal pl = mv.subtract(tc);
                    sb.append("\n  P/L ").append(signedMoney(pl)).append("  (").append(signedPct(pct(pl, tc))).append(")");
                }
            }
            Briefing b = todays.get(p.getTicker());
            if (b != null) {
                sb.append("\n  today: ").append(emoji(b.getSignal())).append(" ").append(b.getSignal())
                        .append(" ").append(confidence(b.getConfidence()));
            }
        }

        if (totalValue.signum() > 0) {
            sb.append("\n\n— Total value ").append(money(totalValue));
            if (totalCost.signum() > 0) {
                BigDecimal pl = totalValue.subtract(totalCost);
                sb.append("  (").append(signedMoney(pl)).append(", ").append(signedPct(pct(pl, totalCost))).append(")");
            }
        }
        return sb.toString();
    }

    private Map<String, Briefing> todaysBriefingsByTicker() {
        Map<String, Briefing> byTicker = new HashMap<>();
        for (Briefing b : briefings.findByBriefingDateOrderByCreatedAtDesc(LocalDate.now())) {
            if (b.getTicker() != null) {
                byTicker.putIfAbsent(b.getTicker(), b);   // list is newest-first, so first wins
            }
        }
        return byTicker;
    }

    private BigDecimal quotePrice(String ticker) {
        try {
            JsonNode q = finnhub.quote(ticker);
            double c = q == null ? 0 : q.path("c").asDouble(0);
            return c > 0 ? BigDecimal.valueOf(c) : null;
        } catch (Exception e) {
            log.debug("quote failed for {}: {}", ticker, e.getMessage());
            return null;
        }
    }

    private static double pct(BigDecimal part, BigDecimal whole) {
        return whole.signum() == 0 ? 0 : part.doubleValue() / whole.doubleValue() * 100.0;
    }

    private static String qty(BigDecimal shares) {
        return shares.stripTrailingZeros().toPlainString();
    }

    private static String money(BigDecimal v) {
        return String.format(Locale.US, "$%,.2f", v.doubleValue());
    }

    private static String signedMoney(BigDecimal v) {
        return String.format(Locale.US, "%s$%,.2f", v.signum() < 0 ? "-" : "+", v.abs().doubleValue());
    }

    private static String signedPct(double p) {
        return String.format(Locale.US, "%+.1f%%", p);
    }

    private static String confidence(BigDecimal c) {
        return c == null ? "—" : c.movePointRight(2).intValue() + "%";
    }

    private static String emoji(Side s) {
        if (s == Side.BUY) return "📈";
        if (s == Side.SELL) return "📉";
        return "➡️";
    }
}
