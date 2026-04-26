package com.plrs.infrastructure.path;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA entity mirror of {@code plrs_ops.learner_path_steps} (V18).
 * Composite PK {@code (path_id, step_order)} via {@link PathStepKey}.
 *
 * <p>Traces to: §3.c.1.4.
 */
@Entity
@Table(name = "learner_path_steps", schema = "plrs_ops")
@IdClass(PathStepKey.class)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LearnerPathStepJpaEntity {

    @Id
    @Column(name = "path_id")
    private Long pathId;

    @Id
    @Column(name = "step_order")
    private Integer stepOrder;

    @Column(name = "content_id", nullable = false)
    private Long contentId;

    @Column(name = "step_status", nullable = false, length = 20)
    private String stepStatus;

    @Column(name = "added_as_review", nullable = false)
    private boolean addedAsReview;

    @Column(name = "reason_in_path", nullable = false, length = 200)
    private String reasonInPath;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
