package com.signaldesk.ingestion.congress;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One disclosed Congress trade, as returned by FMP's {senate,house}-latest endpoints. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CongressTrade(
        String symbol,
        String transactionDate,
        String disclosureDate,
        String firstName,
        String lastName,
        String district,
        String owner,
        String assetType,
        String type,          // "Purchase" | "Sale" | "Sale (Partial)" | ...
        String amount,
        String link
) {
}
