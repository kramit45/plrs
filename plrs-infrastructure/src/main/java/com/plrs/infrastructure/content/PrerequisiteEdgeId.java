package com.plrs.infrastructure.content;

import java.io.Serializable;
import java.util.Objects;

/**
 * JPA composite-key class for {@link PrerequisiteJpaEntity}. Used via
 * {@code @IdClass}. Implemented as a regular class (not a record) because
 * Hibernate 6.4.4's record-component reflection in {@code @IdClass} flow
 * NPEs ({@code "Could not access record components"}). The class form
 * gives Hibernate an unambiguous no-arg constructor and JavaBean
 * accessors while keeping the value semantics via {@link #equals(Object)}
 * and {@link #hashCode()}.
 */
public class PrerequisiteEdgeId implements Serializable {

    private Long contentId;
    private Long prereqContentId;

    public PrerequisiteEdgeId() {}

    public PrerequisiteEdgeId(Long contentId, Long prereqContentId) {
        this.contentId = contentId;
        this.prereqContentId = prereqContentId;
    }

    public Long getContentId() {
        return contentId;
    }

    public Long getPrereqContentId() {
        return prereqContentId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PrerequisiteEdgeId other)) {
            return false;
        }
        return Objects.equals(contentId, other.contentId)
                && Objects.equals(prereqContentId, other.prereqContentId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(contentId, prereqContentId);
    }

    @Override
    public String toString() {
        return "PrerequisiteEdgeId(" + contentId + ", " + prereqContentId + ")";
    }
}
