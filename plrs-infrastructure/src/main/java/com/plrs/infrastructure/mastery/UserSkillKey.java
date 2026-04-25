package com.plrs.infrastructure.mastery;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA composite-key class for {@link UserSkillJpaEntity}. Regular
 * class (not record) — Hibernate 6.4.4's {@code @IdClass} reflection
 * NPEs on records.
 */
public class UserSkillKey implements Serializable {

    private UUID userId;
    private Long topicId;

    public UserSkillKey() {}

    public UserSkillKey(UUID userId, Long topicId) {
        this.userId = userId;
        this.topicId = topicId;
    }

    public UUID getUserId() {
        return userId;
    }

    public Long getTopicId() {
        return topicId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserSkillKey other)) {
            return false;
        }
        return Objects.equals(userId, other.userId) && Objects.equals(topicId, other.topicId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, topicId);
    }
}
