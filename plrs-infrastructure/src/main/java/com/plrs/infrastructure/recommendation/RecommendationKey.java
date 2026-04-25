package com.plrs.infrastructure.recommendation;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA composite-key class for {@link RecommendationJpaEntity}. Regular
 * class (not record) — Hibernate 6.4.4's {@code @IdClass} reflection
 * NPEs on records, same constraint as
 * {@code com.plrs.infrastructure.mastery.UserSkillKey} and
 * {@code com.plrs.infrastructure.interaction.InteractionKey}.
 */
public class RecommendationKey implements Serializable {

    private UUID userId;
    private Long contentId;
    private Instant createdAt;

    public RecommendationKey() {}

    public RecommendationKey(UUID userId, Long contentId, Instant createdAt) {
        this.userId = userId;
        this.contentId = contentId;
        this.createdAt = createdAt;
    }

    public UUID getUserId() {
        return userId;
    }

    public Long getContentId() {
        return contentId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RecommendationKey other)) {
            return false;
        }
        return Objects.equals(userId, other.userId)
                && Objects.equals(contentId, other.contentId)
                && Objects.equals(createdAt, other.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, contentId, createdAt);
    }
}
