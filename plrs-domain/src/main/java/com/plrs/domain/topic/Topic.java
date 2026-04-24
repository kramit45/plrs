package com.plrs.domain.topic;

import com.plrs.domain.common.AuditFields;
import com.plrs.domain.common.DomainInvariantException;
import com.plrs.domain.common.DomainValidationException;
import java.util.Objects;
import java.util.Optional;

/**
 * Aggregate root representing a learnable topic — a node in the curriculum
 * hierarchy PLRS recommends against. Topics form an optional tree via
 * {@link #parentTopicId()}: a root topic has {@link Optional#empty()}; a
 * child points at its parent. The tree can be arbitrarily deep.
 *
 * <p>The class is deliberately immutable: fields are {@code private final}
 * and there are no setters. State-changing operations (rename, move,
 * re-parent) are explicitly out of Iteration 2 scope; if added later they
 * must return a new instance rather than mutating the receiver.
 *
 * <p>Two construction paths are provided:
 *
 * <ul>
 *   <li>{@link #rehydrate(TopicId, String, String, Optional, AuditFields)} —
 *       the persistence path, used by the repository adapter when loading a
 *       row. All fields are trusted as the caller's responsibility; this
 *       path only runs validation.
 *   <li>Fresh topics are created via {@link TopicDraft} and the repository's
 *       {@code save} method: the draft carries everything except the id,
 *       the repository generates a {@code BIGSERIAL} id per §3.c.4 and
 *       returns a fully-populated {@link Topic}.
 * </ul>
 *
 * <p>Invariants enforced by the canonical constructor:
 *
 * <ul>
 *   <li>{@code id}, {@code name}, {@code parentTopicId}, {@code audit} are all
 *       non-null ({@link DomainValidationException}); {@code description} is
 *       the sole nullable field.
 *   <li>{@code name} trimmed is non-blank and 1..120 characters per the
 *       {@code topics.name VARCHAR(120)} column and {@code topics_name_nn}
 *       CHECK ({@link DomainInvariantException}).
 *   <li>{@code description}, when present, is at most 500 characters per
 *       §3.c.1.3.
 * </ul>
 *
 * <p>Cycle detection (a topic whose chain of ancestors loops back to itself)
 * is <b>not</b> a domain concern in Iteration 2: the {@code topics.parent_topic_id}
 * FK uses {@code ON DELETE SET NULL} which mechanically prevents orphan
 * cycles on the write path, and a nightly integrity check in Iteration 4
 * catches pathological inserts. Keeping domain pure of graph-walking logic
 * avoids pulling a repository dependency into the aggregate.
 *
 * <p>Equality is <b>identity-based</b>: two topics are equal iff their
 * {@link TopicId}s are equal, regardless of whether their names,
 * descriptions, parents, or audit fields differ — the conventional
 * aggregate-root equality semantic.
 *
 * <p>Traces to: §3.c.1.3 (topics DDL with self-FK), FR-07 (topic hierarchy).
 */
public final class Topic {

    static final int MAX_NAME_LENGTH = 120;
    static final int MAX_DESCRIPTION_LENGTH = 500;

    private final TopicId id;
    private final String name;
    private final String description;
    private final Optional<TopicId> parentTopicId;
    private final AuditFields audit;

    private Topic(
            TopicId id,
            String name,
            String description,
            Optional<TopicId> parentTopicId,
            AuditFields audit) {
        if (id == null) {
            throw new DomainValidationException("Topic id must not be null");
        }
        if (name == null) {
            throw new DomainValidationException("Topic name must not be null");
        }
        if (parentTopicId == null) {
            throw new DomainValidationException(
                    "Topic parentTopicId must not be null (use Optional.empty() for a root topic)");
        }
        if (audit == null) {
            throw new DomainValidationException("Topic audit must not be null");
        }
        String trimmedName = name.trim();
        if (trimmedName.isEmpty()) {
            throw new DomainInvariantException("Topic name must not be blank");
        }
        if (trimmedName.length() > MAX_NAME_LENGTH) {
            throw new DomainInvariantException(
                    "Topic name must be at most " + MAX_NAME_LENGTH
                            + " characters, got " + trimmedName.length());
        }
        if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new DomainValidationException(
                    "Topic description must be at most " + MAX_DESCRIPTION_LENGTH
                            + " characters, got " + description.length());
        }
        this.id = id;
        this.name = trimmedName;
        this.description = description;
        this.parentTopicId = parentTopicId;
        this.audit = audit;
    }

    /**
     * Reconstructs a topic from persisted state. All fields are trusted as
     * the caller's responsibility; this path only runs validation.
     */
    public static Topic rehydrate(
            TopicId id,
            String name,
            String description,
            Optional<TopicId> parentTopicId,
            AuditFields audit) {
        return new Topic(id, name, description, parentTopicId, audit);
    }

    public TopicId id() {
        return id;
    }

    public String name() {
        return name;
    }

    /**
     * The topic's human-readable description, or {@link Optional#empty()} if
     * none was supplied. Stored internally as a nullable {@code String}
     * because a missing description maps to SQL {@code NULL} rather than an
     * empty string.
     */
    public Optional<String> description() {
        return Optional.ofNullable(description);
    }

    public Optional<TopicId> parentTopicId() {
        return parentTopicId;
    }

    public AuditFields audit() {
        return audit;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Topic other)) {
            return false;
        }
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Topic{id=" + id + ", name='" + name + "', parentTopicId=" + parentTopicId + "}";
    }
}
