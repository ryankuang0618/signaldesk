package com.signaldesk.domain;

import com.signaldesk.domain.enums.PositionSource;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/** A holding in your (paper) portfolio, synced from Alpaca or entered manually. */
@Entity
@Table(name = "portfolio_position")
@Getter
@Setter
@NoArgsConstructor
public class PortfolioPosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String ticker;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal shares;

    @Column(name = "cost_basis", precision = 18, scale = 4)
    private BigDecimal costBasis;

    @Column(name = "market_value", precision = 18, scale = 4)
    private BigDecimal marketValue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PositionSource source = PositionSource.ALPACA;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
