package com.signaldesk.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.signaldesk.domain.enums.Side;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/** An alert queued for / delivered to LINE. A null sentAt means it hasn't been pushed yet. */
@Entity
@Table(name = "alert")
@Getter
@Setter
@NoArgsConstructor
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String ticker;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Side signal;

    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(columnDefinition = "text")
    private String reason;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "briefing_id")
    private Briefing briefing;

    @Column(name = "sent_at")
    private Instant sentAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
