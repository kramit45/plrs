package com.plrs.infrastructure.topic;

import com.plrs.domain.common.AuditFields;
import com.plrs.domain.topic.Topic;
import com.plrs.domain.topic.TopicDraft;
import com.plrs.domain.topic.TopicId;
import com.plrs.infrastructure.user.AuditFieldsEmbeddable;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Bridge between the {@link Topic} aggregate (domain) and
 * {@link TopicJpaEntity} (infrastructure), plus the draft-to-entity
 * conversion used on inserts.
 *
 * <p>Implemented as a Spring {@link Component} rather than a MapStruct
 * {@code @Mapper} interface for the same reason as
 * {@code com.plrs.infrastructure.user.UserMapper}: {@link Topic} has no
 * public constructor and no setters — the only reconstitution path is
 * {@link Topic#rehydrate} — so MapStruct's annotation processor cannot
 * generate a valid implementation. The factory-only design of the domain
 * aggregate is deliberate and takes precedence over matching the step
 * prompt's wording.
 *
 * <p>Null-safety: {@code null} arguments pass through as {@code null} (callers
 * only hit that path via {@code Optional#map}). {@code Optional<TopicId>}
 * on the domain side maps to a nullable {@link Long} on the entity side —
 * {@link Optional#empty()} produces {@code null} and vice versa.
 *
 * <p>Traces to: §3.a (infra maps domain ↔ JPA), §3.c.1.3 (topics schema).
 */
@Component
public class TopicMapper {

    public Topic toDomain(TopicJpaEntity entity) {
        if (entity == null) {
            return null;
        }
        return Topic.rehydrate(
                toTopicId(entity.getId()),
                entity.getTopicName(),
                entity.getDescription(),
                toOptionalTopicId(entity.getParentTopicId()),
                toAuditFields(entity.getAudit()));
    }

    public TopicJpaEntity toEntity(Topic topic) {
        if (topic == null) {
            return null;
        }
        return new TopicJpaEntity(
                topic.id().value(),
                topic.name(),
                topic.description().orElse(null),
                topic.parentTopicId().map(TopicId::value).orElse(null),
                toAuditFieldsEmbeddable(topic.audit()));
    }

    /**
     * Produces an entity suitable for a fresh insert — id is {@code null}
     * so the IDENTITY column triggers a database-side assignment. After
     * {@code jpa.save(...)}, the returned entity carries the generated id
     * and can be rehydrated via {@link #toDomain(TopicJpaEntity)}.
     */
    public TopicJpaEntity toEntity(TopicDraft draft) {
        if (draft == null) {
            return null;
        }
        return new TopicJpaEntity(
                null,
                draft.name(),
                draft.description(),
                draft.parentTopicId().map(TopicId::value).orElse(null),
                toAuditFieldsEmbeddable(draft.audit()));
    }

    public TopicId toTopicId(Long value) {
        return value == null ? null : TopicId.of(value);
    }

    public Optional<TopicId> toOptionalTopicId(Long value) {
        return value == null ? Optional.empty() : Optional.of(TopicId.of(value));
    }

    /**
     * Reconstructs an {@link AuditFields} from its embeddable counterpart.
     * Same lifecycle-simulation approach as {@code UserMapper#toAuditFields}:
     * initialise at {@code createdAt}, then touch to {@code updatedAt}. The
     * V4 migration does not install a CHECK on {@code updated_at >= created_at}
     * for topics (unlike the users table), so this method relies on the
     * embeddable's consistency guarantees upstream.
     */
    public AuditFields toAuditFields(AuditFieldsEmbeddable embeddable) {
        if (embeddable == null) {
            return null;
        }
        return AuditFields.initial(embeddable.getCreatedBy(), embeddable.getCreatedAt())
                .touchedAt(embeddable.getUpdatedAt());
    }

    public AuditFieldsEmbeddable toAuditFieldsEmbeddable(AuditFields audit) {
        if (audit == null) {
            return null;
        }
        return new AuditFieldsEmbeddable(audit.createdAt(), audit.updatedAt(), audit.createdBy());
    }
}
