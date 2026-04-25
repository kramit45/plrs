package com.plrs.infrastructure.mastery;

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
 * JPA entity mirror of one row in {@code plrs_ops.user_skills} (V12).
 * Composite PK {@code (user_id, topic_id)} via {@link UserSkillKey}.
 *
 * <p>Traces to: §3.c.1.4, §3.c.5.7.
 */
@Entity
@Table(name = "user_skills", schema = "plrs_ops")
@IdClass(UserSkillKey.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserSkillJpaEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Column(name = "topic_id")
    private Long topicId;

    @Column(name = "mastery_score", nullable = false, precision = 4, scale = 3)
    private BigDecimal masteryScore;

    @Column(name = "confidence", nullable = false, precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UserSkillJpaEntity(
            UUID userId,
            Long topicId,
            BigDecimal masteryScore,
            BigDecimal confidence,
            Instant updatedAt) {
        this.userId = userId;
        this.topicId = topicId;
        this.masteryScore = masteryScore;
        this.confidence = confidence;
        this.updatedAt = updatedAt;
    }
}
