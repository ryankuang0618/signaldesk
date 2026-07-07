package com.signaldesk.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/** A company news headline with optional sentiment, keyed loosely to a ticker. */
@Entity
@Table(name = "news_item")
@Getter
@Setter
@NoArgsConstructor
public class NewsItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 16)
    private String ticker;

    @Column(nullable = false, columnDefinition = "text")
    private String headline;

    @Column(length = 1024)
    private String url;

    @Column(length = 128)
    private String source;

    @Column(name = "published_at")
    private Instant publishedAt;

    /** -1.000 (bearish) .. 1.000 (bullish). */
    @Column(precision = 4, scale = 3)
    private BigDecimal sentiment;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
