package com.plrs.infrastructure.topic;

import com.plrs.infrastructure.user.AuditFieldsEmbeddable;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * JPA entity mirror of the {@code com.plrs.domain.topic.Topic} aggregate.
 * The mapper in this package is the sole bridge between the two — call
 * sites outside infrastructure never see this class, so the domain
 * remains pure.
 *
 * <p>Primary-key strategy is {@link GenerationType#IDENTITY} so that
 * Postgres's {@code BIGSERIAL} sequence assigns the id at insert time;
 * that matches the {@code TopicDraft → Topic} promotion the adapter
 * exposes. Business fields have no setters — Hibernate populates them
 * via the protected no-arg constructor + reflection, and application
 * code uses the all-args constructor through the mapper.
 *
 * <p>{@code parent_topic_id} is nullable (root topics) and maps to
 * {@link Long} rather than a {@code @ManyToOne} association: the domain
 * aggregate exposes only an {@code Optional<TopicId>}, never a
 * de-referenced parent, so an association would be dead weight and would
 * risk accidental lazy-loading from the mapper.
 *
 * <p>Traces to: §3.a (aggregates), §3.c.1.3 (topics schema).
 */
@Entity
@Table(name = "topics", schema = "plrs_ops")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TopicJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "topic_id")
    private Long id;

    @Column(name = "topic_name", nullable = false, unique = true, length = 120)
    private String topicName;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "parent_topic_id")
    private Long parentTopicId;

    @Embedded private AuditFieldsEmbeddable audit;

    public TopicJpaEntity(
            Long id,
            String topicName,
            String description,
            Long parentTopicId,
            AuditFieldsEmbeddable audit) {
        this.id = id;
        this.topicName = topicName;
        this.description = description;
        this.parentTopicId = parentTopicId;
        this.audit = audit;
    }
}
