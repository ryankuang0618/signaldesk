package com.signaldesk.ingestion.edgar;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The parsed essence of a Form 4, reduced to a directional signal.
 *
 * @param issuerSymbol  ticker of the company
 * @param ownerName     the reporting insider
 * @param officerTitle  their role (may be null)
 * @param latestDate    most recent transaction date in the filing
 * @param openMarket    true if any open-market purchase (P) or sale (S) is present — the high-signal case
 * @param openMarketNet signed net shares across P/S transactions (+ acquired, − disposed)
 * @param totalNet      signed net shares across all transactions (includes grants, tax withholding, etc.)
 */
public record Form4Result(
        String issuerSymbol,
        String ownerName,
        String officerTitle,
        LocalDate latestDate,
        boolean openMarket,
        BigDecimal openMarketNet,
        BigDecimal totalNet
) {
    /** The net that best represents intent: open-market moves when present, else everything. */
    public BigDecimal signalNet() {
        return openMarket ? openMarketNet : totalNet;
    }
}
