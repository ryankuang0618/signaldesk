package com.signaldesk.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/** A company whose insider (Form 4) activity we watch. */
@Entity
@Table(name = "tracked_issuer")
@Getter
@Setter
@NoArgsConstructor
public class TrackedIssuer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String ticker;

    /** 10-digit zero-padded SEC CIK. */
    @Column(nullable = false, length = 10)
    private String cik;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
