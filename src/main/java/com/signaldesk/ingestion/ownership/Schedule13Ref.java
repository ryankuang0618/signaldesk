package com.signaldesk.ingestion.ownership;

import java.time.LocalDate;

/**
 * One Schedule 13D/13G filing reference from a fund's EDGAR submissions.
 *
 * @param accessionNumber unique filing id (dedup key)
 * @param filingDate      when it was filed
 * @param form            the form type ("SC 13D", "SC 13D/A", "SC 13G", "SC 13G/A")
 */
public record Schedule13Ref(String accessionNumber, LocalDate filingDate, String form) {

    public boolean isActivist() {
        return form != null && form.startsWith("SC 13D");
    }

    public boolean isAmendment() {
        return form != null && form.endsWith("/A");
    }
}
