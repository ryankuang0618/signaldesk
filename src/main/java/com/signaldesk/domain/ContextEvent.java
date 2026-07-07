package com.signaldesk.domain;

import com.signaldesk.domain.enums.ContextType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** A validation input the AI weighs against signals: analyst ratings, earnings, 8-K, technicals. */
@Entity
@Table(name = "context_event")
@Getter
@Setter
@NoArgsConstructor
public class ContextEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String ticker;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ContextType type;

    @Column(columnDefinition = "text")
    private String summary;

    /** Dedup key, unique per type (8-K accession, ticker+period, ticker+date, ...). */
    @Column(length = 255)
    private String ref;

    /** Raw structured detail, stored as JSONB. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String payload;

    @Column(name = "event_at")
    private Instant eventAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
