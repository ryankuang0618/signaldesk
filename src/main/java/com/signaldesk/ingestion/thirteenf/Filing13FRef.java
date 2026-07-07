package com.signaldesk.ingestion.thirteenf;

import java.time.LocalDate;

/** A reference to one 13F-HR filing. */
public record Filing13FRef(String accessionNumber, LocalDate filingDate, LocalDate periodOfReport) {
}
