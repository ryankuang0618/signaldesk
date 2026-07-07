package com.signaldesk.ingestion.thirteenf;

import java.math.BigDecimal;

/** One aggregated position parsed from a 13F information table. */
public record Holding(String cusip, String issuerName, BigDecimal shares, BigDecimal value) {
}
