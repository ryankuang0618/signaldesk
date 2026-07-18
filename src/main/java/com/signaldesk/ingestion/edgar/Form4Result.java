package com.signaldesk.ingestion.edgar;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The parsed essence of a Form 4, reduced to a directional signal plus the fields an insider-quality
 * score needs: who traded (role), how (transaction type), and how much (dollar value, % of holdings).
 *
 * @param issuerSymbol     ticker of the company
 * @param ownerName        the reporting insider
 * @param officerTitle     their officer title, if any (e.g. "Chief Executive Officer")
 * @param latestDate       most recent transaction date in the filing
 * @param openMarket       true if any open-market purchase (P) or sale (S) is present — the high-signal case
 * @param openMarketNet    signed net shares across P/S transactions (+ acquired, − disposed)
 * @param totalNet         signed net shares across all transactions (includes grants, tax withholding, etc.)
 * @param isOfficer        the reporting owner is a company officer
 * @param isDirector       the reporting owner is a director
 * @param isTenPercentOwner the reporting owner is a 10%+ holder
 * @param primaryCode      transaction code of the largest transaction (P, S, A, M, F, G, ...)
 * @param dollarValue      approximate $ value of the open-market transactions (shares × price), or null
 * @param pctOfHoldings    signal size as a fraction of holdings after the trade (0.0–1.0+), or null
 */
public record Form4Result(
        String issuerSymbol,
        String ownerName,
        String officerTitle,
        LocalDate latestDate,
        boolean openMarket,
        BigDecimal openMarketNet,
        BigDecimal totalNet,
        boolean isOfficer,
        boolean isDirector,
        boolean isTenPercentOwner,
        String primaryCode,
        BigDecimal dollarValue,
        BigDecimal pctOfHoldings
) {
    /** The net that best represents intent: open-market moves when present, else everything. */
    public BigDecimal signalNet() {
        return openMarket ? openMarketNet : totalNet;
    }
}
