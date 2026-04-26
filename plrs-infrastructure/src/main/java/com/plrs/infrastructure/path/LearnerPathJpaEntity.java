package com.plrs.infrastructure.path;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity mirror of one row in {@code plrs_ops.learner_paths} (V18).
 * Children ({@link LearnerPathStepJpaEntity}) are loaded explicitly by
 * the adapter rather than via {@code @OneToMany}: composite-PK
 * children would force {@code @MapsId}/{@code insertable=false}
 * gymnastics that don't pay for themselves at this aggregate's small
 * step counts.
 *
 * <p>{@code mastery_start_snapshot} and {@code mastery_end_snapshot}
 * are JSONB strings — the mapper Jackson-serialises the
 * {@code Map<TopicId, MasteryScore>} into a flat {@code {topicIdStr →
 * mastery}} object on the way in.
 *
 * <p>Traces to: §3.c.1.4, §3.b.4.3.
 */
@Entity
@Table(name = "learner_paths", schema = "plrs_ops")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LearnerPathJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "path_id")
    private Long pathId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "target_topic_id", nullable = false)
    private Long targetTopicId;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "paused_at")
    private Instant pausedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "abandoned_at")
    private Instant abandonedAt;

    @Column(name = "superseded_at")
    private Instant supersededAt;

    @Column(name = "superseded_by")
    private Long supersededBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "mastery_start_snapshot", nullable = false, columnDefinition = "JSONB")
    private String masteryStartSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "mastery_end_snapshot", columnDefinition = "JSONB")
    private String masteryEndSnapshot;
}
