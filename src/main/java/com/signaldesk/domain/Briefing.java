package com.signaldesk.domain;

import com.signaldesk.domain.enums.Side;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Claude's stored daily research output. A null ticker means a global market briefing. */
@Entity
@Table(name = "briefing")
@Getter
@Setter
@NoArgsConstructor
public class Briefing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "briefing_date", nullable = false)
    private LocalDate briefingDate;

    @Column(length = 16)
    private String ticker;

    /** The SWING (weeks) read — the headline signal used by alerts, the portfolio line, and the backtest. */
    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    private Side signal;

    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

    // Short-horizon (days) read: news, 8-K, earnings surprises, sudden analyst moves.
    @Enumerated(EnumType.STRING)
    @Column(name = "short_signal", length = 8)
    private Side shortSignal;

    @Column(name = "short_confidence", precision = 4, scale = 3)
    private BigDecimal shortConfidence;

    // Long-horizon (months) read: 13F fund positioning and fundamentals.
    @Enumerated(EnumType.STRING)
    @Column(name = "long_signal", length = 8)
    private Side longSignal;

    @Column(name = "long_confidence", precision = 4, scale = 3)
    private BigDecimal longConfidence;

    @Column(nullable = false, columnDefinition = "text")
    private String summary;

    @Column(length = 64)
    private String model;

    // --- Backtest / track record ---
    /**
     * Price of the ticker when this briefing was made — the shared entry for all horizons. Per-horizon
     * scoring lives in {@link BriefingScore} rows created from this briefing.
     */
    @Column(name = "entry_price", precision = 18, scale = 4)
    private BigDecimal entryPrice;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
