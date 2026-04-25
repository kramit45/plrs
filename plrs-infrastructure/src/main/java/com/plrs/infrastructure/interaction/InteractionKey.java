package com.plrs.infrastructure.interaction;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA composite-key class for {@link InteractionJpaEntity}. Implemented
 * as a regular class (not a record) because Hibernate 6.4.4's
 * record-component reflection in {@code @IdClass} flow NPEs ({@code
 * "Could not access record components"}); same workaround as
 * {@code PrerequisiteEdgeId}.
 */
public class InteractionKey implements Serializable {

    private UUID userId;
    private Long contentId;
    private Instant occurredAt;

    public InteractionKey() {}

    public InteractionKey(UUID userId, Long contentId, Instant occurredAt) {
        this.userId = userId;
        this.contentId = contentId;
        this.occurredAt = occurredAt;
    }

    public UUID getUserId() {
        return userId;
    }

    public Long getContentId() {
        return contentId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof InteractionKey other)) {
            return false;
        }
        return Objects.equals(userId, other.userId)
                && Objects.equals(contentId, other.contentId)
                && Objects.equals(occurredAt, other.occurredAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, contentId, occurredAt);
    }
}
