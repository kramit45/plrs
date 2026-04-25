package com.plrs.infrastructure.quiz;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity mirror of one row in {@code plrs_ops.quiz_attempts} (V11).
 * {@code per_item_json} maps via {@link JdbcTypeCode}({@link SqlTypes#JSON})
 * so the Java {@code String} round-trips through Postgres {@code JSONB}.
 *
 * <p>The mapper ({@code QuizAttemptMapper}) Jackson-serialises the
 * domain {@code List<PerItemFeedback>} and {@code Map<TopicId, BigDecimal>
 * topicWeights} into a single JSON object stored in {@code per_item_json}
 * (keys: {@code per_item}, {@code topic_weights}).
 *
 * <p>Traces to: §3.c.1.4 (quiz_attempts schema), FR-20.
 */
@Entity
@Table(name = "quiz_attempts", schema = "plrs_ops")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuizAttemptJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "attempt_id")
    private Long attemptId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "content_id", nullable = false)
    private Long contentId;

    @Column(name = "score", nullable = false, precision = 5, scale = 2)
    private BigDecimal score;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "per_item_json", nullable = false, columnDefinition = "JSONB")
    private String perItemJson;

    @Column(name = "policy_version", nullable = false, length = 20)
    private String policyVersion;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    public QuizAttemptJpaEntity(
            Long attemptId,
            UUID userId,
            Long contentId,
            BigDecimal score,
            String perItemJson,
            String policyVersion,
            Instant attemptedAt) {
        this.attemptId = attemptId;
        this.userId = userId;
        this.contentId = contentId;
        this.score = score;
        this.perItemJson = perItemJson;
        this.policyVersion = policyVersion;
        this.attemptedAt = attemptedAt;
    }
}
