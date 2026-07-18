package com.signaldesk.domain;

import com.signaldesk.domain.enums.Horizon;
import com.signaldesk.domain.enums.Side;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One horizon's scorable call for a briefing. Created (pending) when the briefing is made, then filled
 * in by the backtest evaluator once {@link #dueDate} has passed. Self-contained (carries ticker + entry
 * price) so scoring needs no join back to the briefing.
 */
@Entity
@Table(name = "briefing_score",
        uniqueConstraints = @UniqueConstraint(name = "uq_briefing_score", columnNames = {"briefing_id", "horizon"}))
@Getter
@Setter
@NoArgsConstructor
public class BriefingScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "briefing_id", nullable = false)
    private Long briefingId;

    @Column(nullable = false, length = 16)
    private String ticker;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Horizon horizon;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Side signal;

    @Column(name = "horizon_days", nullable = false)
    private Integer horizonDays;

    /** Ticker price when the briefing was made — the entry the return is measured from. */
    @Column(name = "entry_price", nullable = false, precision = 18, scale = 4)
    private BigDecimal entryPrice;

    /** briefing_date + horizon_days: the date this call becomes scorable. */
    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "exit_price", precision = 18, scale = 4)
    private BigDecimal exitPrice;

    @Column(name = "return_pct", precision = 10, scale = 3)
    private BigDecimal returnPct;

    /** Whether the directional call (BUY→up / SELL→down) was right. Null until evaluated. */
    @Column(name = "correct")
    private Boolean correct;

    @Column(name = "evaluated_at")
    private Instant evaluatedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
