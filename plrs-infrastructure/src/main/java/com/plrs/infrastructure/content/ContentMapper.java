package com.plrs.infrastructure.content;

import com.plrs.domain.common.AuditFields;
import com.plrs.domain.content.Content;
import com.plrs.domain.content.ContentDraft;
import com.plrs.domain.content.ContentId;
import com.plrs.domain.topic.TopicId;
import com.plrs.domain.user.UserId;
import com.plrs.infrastructure.user.AuditFieldsEmbeddable;
import java.util.HashSet;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Bridge between the {@link Content} aggregate (domain) and
 * {@link ContentJpaEntity} (infrastructure), plus the draft-to-entity
 * conversion used on inserts.
 *
 * <p>Implemented as a Spring {@link Component} rather than a MapStruct
 * {@code @Mapper} interface for the same reason as
 * {@code com.plrs.infrastructure.user.UserMapper} and
 * {@code com.plrs.infrastructure.topic.TopicMapper}: {@link Content} has
 * no public constructor and no setters — the only reconstitution path is
 * {@link Content#rehydrate} — so MapStruct's annotation processor cannot
 * generate a valid implementation.
 *
 * <p>Null-safety: {@code null} arguments pass through as {@code null}
 * (callers only hit that path via {@code Optional#map}). {@code Optional}
 * fields on the domain side map to nullable scalars on the entity side.
 *
 * <p>Traces to: §3.a (infra maps domain ↔ JPA), §3.c.1.3 (content schema),
 * §3.c.8 (mapping discipline).
 */
@Component
public class ContentMapper {

    public Content toDomain(ContentJpaEntity entity) {
        if (entity == null) {
            return null;
        }
        return Content.rehydrate(
                ContentId.of(entity.getId()),
                TopicId.of(entity.getTopicId()),
                entity.getTitle(),
                entity.getCtype(),
                entity.getDifficulty(),
                entity.getEstMinutes(),
                entity.getUrl(),
                Optional.ofNullable(entity.getDescription()),
                new HashSet<>(entity.getTags()),
                Optional.ofNullable(entity.getCreatedBy()).map(UserId::of),
                toAuditFields(entity.getAudit()));
    }

    public ContentJpaEntity toEntity(Content content) {
        if (content == null) {
            return null;
        }
        return new ContentJpaEntity(
                content.id().value(),
                content.topicId().value(),
                content.title(),
                content.ctype(),
                content.difficulty(),
                content.estMinutes(),
                content.url(),
                content.description().orElse(null),
                content.createdBy().map(UserId::value).orElse(null),
                new HashSet<>(content.tags()),
                toAuditFieldsEmbeddable(content.audit()));
    }

    /**
     * Produces an entity suitable for a fresh insert — id is {@code null}
     * so the IDENTITY column triggers a database-side assignment. After
     * {@code jpa.save(...)}, the returned entity carries the generated id
     * and can be rehydrated via {@link #toDomain(ContentJpaEntity)}.
     */
    public ContentJpaEntity toEntity(ContentDraft draft) {
        if (draft == null) {
            return null;
        }
        return new ContentJpaEntity(
                null,
                draft.topicId().value(),
                draft.title(),
                draft.ctype(),
                draft.difficulty(),
                draft.estMinutes(),
                draft.url(),
                draft.description().orElse(null),
                draft.createdBy().map(UserId::value).orElse(null),
                new HashSet<>(draft.tags()),
                toAuditFieldsEmbeddable(draft.audit()));
    }

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
