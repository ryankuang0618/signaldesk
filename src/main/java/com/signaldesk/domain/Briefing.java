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

    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    private Side signal;

    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(nullable = false, columnDefinition = "text")
    private String summary;

    @Column(length = 64)
    private String model;

    // --- Backtest / track record ---
    /** Price of the ticker when this briefing was made. */
    @Column(name = "entry_price", precision = 18, scale = 4)
    private BigDecimal entryPrice;

    @Column(name = "horizon_days")
    private Integer horizonDays;

    @Column(name = "evaluated_at")
    private Instant evaluatedAt;

    @Column(name = "exit_price", precision = 18, scale = 4)
    private BigDecimal exitPrice;

    /** Percent move from entry to exit over the horizon. */
    @Column(name = "return_pct", precision = 10, scale = 3)
    private BigDecimal returnPct;

    /** Whether the directional call (BUY→up / SELL→down) was right. Null until evaluated. */
    @Column(name = "correct")
    private Boolean correct;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
