package com.plrs.infrastructure.recommendation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * JPA entity mirror of one row in {@code plrs_ops.recommendations}
 * (V14). Composite primary key {@code (user_id, content_id, created_at)}
 * via {@link RecommendationKey} as the {@code @IdClass}.
 *
 * <p>Traces to: §3.c.1.4 (recommendations schema), FR-26/27/29.
 */
@Entity
@Table(name = "recommendations", schema = "plrs_ops")
@IdClass(RecommendationKey.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecommendationJpaEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Column(name = "content_id")
    private Long contentId;

    @Id
    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "score", nullable = false, precision = 6, scale = 4)
    private BigDecimal score;

    @Column(name = "rank_position", nullable = false)
    private short rankPosition;

    @Column(name = "reason_text", nullable = false, length = 200)
    private String reasonText;

    @Column(name = "model_variant", nullable = false, length = 30)
    private String modelVariant;

    @Column(name = "clicked_at")
    private Instant clickedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public RecommendationJpaEntity(
            UUID userId,
            Long contentId,
            Instant createdAt,
            BigDecimal score,
            short rankPosition,
            String reasonText,
            String modelVariant,
            Instant clickedAt,
            Instant completedAt) {
        this.userId = userId;
        this.contentId = contentId;
        this.createdAt = createdAt;
        this.score = score;
        this.rankPosition = rankPosition;
        this.reasonText = reasonText;
        this.modelVariant = modelVariant;
        this.clickedAt = clickedAt;
        this.completedAt = completedAt;
    }
}
