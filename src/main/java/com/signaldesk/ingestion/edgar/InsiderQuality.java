package com.signaldesk.ingestion.edgar;

import com.signaldesk.domain.enums.Side;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Grades a Form 4 into a quality score + a short human descriptor, so a CEO's $2M open-market buy is
 * weighted far above an automatic tax-withholding sale. Not all insider trades are equal:
 * <ul>
 *   <li><b>Type</b> — open-market purchases/sales (P/S) reflect intent; grants (A), option exercises
 *       (M), tax withholding (F) and gifts (G) are noise.</li>
 *   <li><b>Role</b> — a CEO/CFO carries more information than a director or generic officer.</li>
 *   <li><b>Size</b> — dollar value and the trade's size relative to existing holdings.</li>
 * </ul>
 * Confidence here is signal <em>strength</em> (how meaningful), independent of direction — a big
 * open-market CEO sale is a strong SELL signal just as a big buy is a strong BUY.
 */
public final class InsiderQuality {

    private InsiderQuality() {
    }

    /** A graded confidence in [0.05, 0.98] plus a descriptor for the AI prompt. */
    public record Assessment(BigDecimal score, String note) {
    }

    public static Assessment assess(Form4Result r) {
        Side side = r.signalNet().signum() < 0 ? Side.SELL : Side.BUY;
        String code = r.primaryCode() == null ? "" : r.primaryCode();

        double base = baseScore(r.openMarket(), side, code);
        double role = roleBonus(r);
        double magnitude = r.openMarket() ? magnitudeBonus(r) : 0.0;

        double score = clamp(base + role + magnitude, 0.05, 0.98);
        return new Assessment(BigDecimal.valueOf(Math.round(score * 1000) / 1000.0), note(r, side, code));
    }

    private static double baseScore(boolean openMarket, Side side, String code) {
        if (openMarket) {
            return side == Side.BUY ? 0.55 : 0.45;   // sells are noisier (diversification, taxes)
        }
        return switch (code) {
            case "A" -> 0.20;   // grant / award
            case "M" -> 0.20;   // option/derivative exercise
            case "F" -> 0.15;   // shares withheld for taxes
            case "G" -> 0.15;   // gift
            default -> 0.25;
        };
    }

    private static double roleBonus(Form4Result r) {
        String title = r.officerTitle() == null ? "" : r.officerTitle().toLowerCase(Locale.ROOT);
        boolean topOfficer = title.contains("chief executive") || title.contains("ceo")
                || title.contains("chief financial") || title.contains("cfo")
                || title.contains("chief operating") || title.contains("coo")
                || title.contains("president");
        if (topOfficer) {
            return 0.20;
        }
        if (r.isOfficer()) {
            return 0.12;
        }
        if (r.isTenPercentOwner()) {
            return 0.10;
        }
        if (r.isDirector()) {
            return 0.08;
        }
        return 0.0;
    }

    private static double magnitudeBonus(Form4Result r) {
        double byDollar = 0.0;
        if (r.dollarValue() != null) {
            double d = r.dollarValue().doubleValue();
            byDollar = d >= 1_000_000 ? 0.10 : d >= 250_000 ? 0.05 : 0.0;
        }
        double byPct = 0.0;
        if (r.pctOfHoldings() != null) {
            double p = r.pctOfHoldings().doubleValue();
            byPct = p >= 0.50 ? 0.10 : p >= 0.10 ? 0.05 : 0.0;
        }
        return Math.max(byDollar, byPct);
    }

    private static String note(Form4Result r, Side side, String code) {
        StringBuilder sb = new StringBuilder(roleLabel(r)).append(" ").append(typeLabel(r, side, code));
        if (r.openMarket() && r.dollarValue() != null) {
            sb.append(" · ").append(money(r.dollarValue()));
        }
        if (r.openMarket() && r.pctOfHoldings() != null && r.pctOfHoldings().doubleValue() >= 0.05) {
            sb.append(" · ").append(Math.round(r.pctOfHoldings().doubleValue() * 100)).append("% of stake");
        }
        return sb.toString();
    }

    private static String roleLabel(Form4Result r) {
        String title = r.officerTitle() == null ? "" : r.officerTitle().toLowerCase(Locale.ROOT);
        if (title.contains("chief executive") || title.contains("ceo")) return "CEO";
        if (title.contains("chief financial") || title.contains("cfo")) return "CFO";
        if (title.contains("chief operating") || title.contains("coo")) return "COO";
        if (title.contains("president")) return "President";
        if (r.isOfficer()) return "Officer";
        if (r.isTenPercentOwner()) return "10% owner";
        if (r.isDirector()) return "Director";
        return "Insider";
    }

    private static String typeLabel(Form4Result r, Side side, String code) {
        if (r.openMarket()) {
            return side == Side.BUY ? "open-market buy" : "open-market sale";
        }
        return switch (code) {
            case "A" -> "grant";
            case "M" -> "option exercise";
            case "F" -> "tax withholding";
            case "G" -> "gift";
            default -> side == Side.BUY ? "acquisition" : "disposition";
        };
    }

    private static String money(BigDecimal v) {
        double d = v.doubleValue();
        if (d >= 1_000_000) {
            return String.format(Locale.US, "$%.1fM", d / 1_000_000);
        }
        if (d >= 1_000) {
            return String.format(Locale.US, "$%.0fK", d / 1_000);
        }
        return String.format(Locale.US, "$%.0f", d);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
