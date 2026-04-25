package com.plrs.infrastructure.content;

import com.plrs.domain.content.ContentType;
import com.plrs.domain.content.Difficulty;
import com.plrs.infrastructure.user.AuditFieldsEmbeddable;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * JPA entity mirror of the {@code com.plrs.domain.content.Content}
 * aggregate. The mapper in this package is the sole bridge between the
 * two — call sites outside infrastructure never see this class, so the
 * domain remains pure.
 *
 * <p>Primary-key strategy is {@link GenerationType#IDENTITY} so that
 * Postgres's {@code BIGSERIAL} sequence assigns the id at insert time;
 * that matches the {@code ContentDraft → Content} promotion the adapter
 * exposes. Business fields have no setters — Hibernate populates them
 * via the protected no-arg constructor + reflection, and application
 * code uses the all-args constructor through the mapper.
 *
 * <p>{@link #createdBy} is a nullable {@link UUID} (not {@code Long}) to
 * match {@code users.id UUID} from V2__users.sql: the FK type must align
 * or {@code spring.jpa.hibernate.ddl-auto=validate} rejects the entity.
 *
 * <p>Tags are modelled as an {@link ElementCollection} of {@code String}
 * values keyed on {@code content_id} — the generated schema is the
 * {@code content_tags} table from V5__content.sql. The element is loaded
 * eagerly because {@link com.plrs.domain.content.Content#tags()} is part
 * of the aggregate's core identity; skipping the extra round-trip is
 * worth the classic lazy-loading mystery of detached Hibernate entities.
 *
 * <p>Traces to: §3.a (aggregates), §3.c.1.3 (content + content_tags schema).
 */
@Entity
@Table(name = "content", schema = "plrs_ops")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContentJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "content_id")
    private Long id;

    @Column(name = "topic_id", nullable = false)
    private Long topicId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "ctype", nullable = false, length = 15)
    private ContentType ctype;

    @Enumerated(EnumType.STRING)
    @Column(name = "difficulty", nullable = false, length = 15)
    private Difficulty difficulty;

    @Column(name = "est_minutes", nullable = false)
    private int estMinutes;

    @Column(name = "url", nullable = false, columnDefinition = "TEXT")
    private String url;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_by")
    private UUID createdBy;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "content_tags",
            schema = "plrs_ops",
            joinColumns = @JoinColumn(name = "content_id"))
    @Column(name = "tag", nullable = false, length = 60)
    private Set<String> tags = new HashSet<>();

    @Embedded
    @AttributeOverride(
            name = "createdBy",
            column = @Column(name = "audit_created_by", nullable = false, length = 64))
    private AuditFieldsEmbeddable audit;

    public ContentJpaEntity(
            Long id,
            Long topicId,
            String title,
            ContentType ctype,
            Difficulty difficulty,
            int estMinutes,
            String url,
            String description,
            UUID createdBy,
            Set<String> tags,
            AuditFieldsEmbeddable audit) {
        this.id = id;
        this.topicId = topicId;
        this.title = title;
        this.ctype = ctype;
        this.difficulty = difficulty;
        this.estMinutes = estMinutes;
        this.url = url;
        this.description = description;
        this.createdBy = createdBy;
        this.tags = tags;
        this.audit = audit;
    }
}
