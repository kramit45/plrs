package com.plrs.infrastructure.interaction;

import com.plrs.domain.interaction.EventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * JPA entity mirror of one row in {@code plrs_ops.interactions} (V8).
 * Composite primary key {@code (user_id, content_id, occurred_at)} via
 * {@link InteractionKey} as the {@code @IdClass}.
 *
 * <p>{@code event_type} is stored as {@link EnumType#STRING} so the
 * domain {@link EventType} enum and the {@code interactions_type_enum}
 * CHECK constraint stay literally aligned.
 *
 * <p>Traces to: §3.c.1.4 (interactions schema), FR-15 / FR-16 / FR-17.
 */
@Entity
@Table(name = "interactions", schema = "plrs_ops")
@IdClass(InteractionKey.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InteractionJpaEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Column(name = "content_id")
    private Long contentId;

    @Id
    @Column(name = "occurred_at")
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20)
    private EventType eventType;

    @Column(name = "dwell_sec")
    private Integer dwellSec;

    @Column(name = "rating")
    private Integer rating;

    @Column(name = "client_info", length = 200)
    private String clientInfo;

    public InteractionJpaEntity(
            UUID userId,
            Long contentId,
            Instant occurredAt,
            EventType eventType,
            Integer dwellSec,
            Integer rating,
            String clientInfo) {
        this.userId = userId;
        this.contentId = contentId;
        this.occurredAt = occurredAt;
        this.eventType = eventType;
        this.dwellSec = dwellSec;
        this.rating = rating;
        this.clientInfo = clientInfo;
    }
}
