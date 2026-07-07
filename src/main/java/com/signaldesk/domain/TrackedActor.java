package com.signaldesk.domain;

import com.signaldesk.domain.enums.ActorType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/** A market participant we follow: a fund, an insider, a member of Congress, or an eToro trader. */
@Entity
@Table(name = "tracked_actor")
@Getter
@Setter
@NoArgsConstructor
public class TrackedActor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ActorType type;

    /** SEC CIK, congress slug, eToro handle, etc. */
    @Column(name = "external_id", length = 128)
    private String externalId;

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
