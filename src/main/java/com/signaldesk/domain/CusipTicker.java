package com.signaldesk.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/** Cached CUSIP → ticker mapping. A null ticker means "looked up, not found". */
@Entity
@Table(name = "cusip_ticker")
@Getter
@Setter
@NoArgsConstructor
public class CusipTicker {

    @Id
    @Column(length = 16)
    private String cusip;

    @Column(length = 16)
    private String ticker;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public CusipTicker(String cusip, String ticker) {
        this.cusip = cusip;
        this.ticker = ticker;
    }
}
