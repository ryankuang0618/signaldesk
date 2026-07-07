package com.signaldesk.ingestion.edgar;

import java.time.LocalDate;

/** A reference to one Form 4 filing, from the SEC submissions index. */
public record Form4Ref(String accessionNumber, LocalDate filingDate, String primaryDocument) {
}
