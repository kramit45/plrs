package com.plrs.domain.content;

import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.user.UserId;
import java.time.Instant;
import java.util.Optional;

/**
 * One directed edge in the prerequisite DAG: "to consume {@code contentId},
 * the learner should first consume {@code prereqContentId}". Carries the
 * stamp metadata needed for audit ({@code addedAt}, {@code addedBy}) so
 * the use-case layer can record who introduced the dependency.
 *
 * <p>Self-edges are rejected at construction — a piece of content cannot
 * be its own prerequisite. That rule is the cheapest cycle check the
 * domain can do without a graph walker; the full DFS lives on the
 * adapter (step 62) per the
 * {@link PrerequisiteCheckingRepository} port.
 *
 * <p>{@code addedBy} is {@code Optional<UserId>}: system-seeded edges have
 * no human author and use {@link Optional#empty()}.
 *
 * <p>Traces to: §3.c.1.3 (prerequisites table), §3.b.2.3 (no-self-edge
 * invariant), FR-09 (prerequisite tracking).
 */
public record PrerequisiteEdge(
        ContentId contentId, ContentId prereqContentId, Instant addedAt, Optional<UserId> addedBy) {

    public PrerequisiteEdge {
        if (contentId == null) {
            throw new DomainValidationException("PrerequisiteEdge contentId must not be null");
        }
        if (prereqContentId == null) {
            throw new DomainValidationException(
                    "PrerequisiteEdge prereqContentId must not be null");
        }
        if (addedAt == null) {
            throw new DomainValidationException("PrerequisiteEdge addedAt must not be null");
        }
        if (addedBy == null) {
            throw new DomainValidationException(
                    "PrerequisiteEdge addedBy must not be null"
                            + " (use Optional.empty() for system-seeded edges)");
        }
        if (contentId.equals(prereqContentId)) {
            throw new DomainValidationException(
                    "prerequisite edge cannot be self-referential: " + contentId);
        }
    }
}
