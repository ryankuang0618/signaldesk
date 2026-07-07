package com.signaldesk.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** One aggregated 13F holding: a fund's position in a security for a given filing period. */
@Entity
@Table(name = "fund_holding")
@Getter
@Setter
@NoArgsConstructor
public class FundHolding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_id", nullable = false)
    private TrackedActor actor;

    @Column(nullable = false, length = 10)
    private String cik;

    @Column(nullable = false, length = 32)
    private String accession;

    @Column(name = "period_of_report", nullable = false)
    private LocalDate periodOfReport;

    @Column(name = "filed_at")
    private LocalDate filedAt;

    @Column(nullable = false, length = 16)
    private String cusip;

    @Column(name = "issuer_name")
    private String issuerName;

    @Column(length = 16)
    private String ticker;

    @Column(precision = 24, scale = 4)
    private BigDecimal shares;

    @Column(precision = 24, scale = 2)
    private BigDecimal value;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
