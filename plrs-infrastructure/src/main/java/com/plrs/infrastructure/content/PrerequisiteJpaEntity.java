package com.plrs.infrastructure.content;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * JPA entity mirror of one row in {@code plrs_ops.prerequisites}. Composite
 * primary key {@code (content_id, prereq_content_id)} is wired via
 * {@link PrerequisiteEdgeId} as the {@code @IdClass}.
 *
 * <p>{@link #addedBy} is a nullable {@link UUID} matching {@code users.id}
 * (V2). Iter 2 deviated from the step prompts' BIGINT suggestion across
 * three migrations (V5 content.created_by, V7 prerequisites.added_by);
 * the entity types follow the actual column types so
 * {@code spring.jpa.hibernate.ddl-auto=validate} accepts the mapping.
 *
 * <p>Traces to: §3.c.1.3 (prerequisites schema), FR-09.
 */
@Entity
@Table(name = "prerequisites", schema = "plrs_ops")
@IdClass(PrerequisiteEdgeId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PrerequisiteJpaEntity {

    @Id
    @Column(name = "content_id")
    private Long contentId;

    @Id
    @Column(name = "prereq_content_id")
    private Long prereqContentId;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    @Column(name = "added_by")
    private UUID addedBy;

    public PrerequisiteJpaEntity(
            Long contentId, Long prereqContentId, Instant addedAt, UUID addedBy) {
        this.contentId = contentId;
        this.prereqContentId = prereqContentId;
        this.addedAt = addedAt;
        this.addedBy = addedBy;
    }
}
