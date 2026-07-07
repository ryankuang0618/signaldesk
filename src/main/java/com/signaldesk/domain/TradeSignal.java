package com.signaldesk.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.signaldesk.domain.enums.Side;
import com.signaldesk.domain.enums.TradeSource;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** The normalized shape every source (insider, Congress, 13F, options flow, ...) collapses into. */
@Entity
@Table(name = "trade_signal")
@Getter
@Setter
@NoArgsConstructor
public class TradeSignal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TradeSource source;

    @Column(nullable = false, length = 16)
    private String ticker;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Side side;

    // The linked actor is an internal relation; the API exposes actorName instead.
    // (Serializing a lazy proxy here would trigger LazyInitializationException.)
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private TrackedActor actor;

    /** Denormalized actor name — a signal may reference an actor we don't formally track. */
    @Column(name = "actor_name")
    private String actorName;

    /** When the trade actually happened (can lag disclosure by weeks). */
    @Column(name = "transacted_at")
    private LocalDate transactedAt;

    /** When the trade became public. */
    @Column(name = "disclosed_at")
    private Instant disclosedAt;

    /** Optional 0.000 - 1.000 confidence. */
    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

    /** Accession number / external id — deduplicates re-ingested filings (unique with source). */
    @Column(name = "raw_ref")
    private String rawRef;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
