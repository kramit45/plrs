package com.plrs.domain.content;

import java.util.List;

/**
 * Domain port for the prerequisite DAG. Extends
 * {@link PrerequisiteCheckingRepository} so callers that only need cycle
 * detection (e.g. the {@link Content} aggregate) can depend on the
 * narrower interface, while use cases that mutate edges depend on this
 * full surface.
 *
 * <p>The adapter (step 62, infrastructure module) implements this on top
 * of {@code plrs_ops.prerequisites} (V7) with a recursive CTE for the
 * cycle walk. The DB-side {@code prereq_no_self} CHECK + composite PK
 * back-stop the application-level invariants enforced via
 * {@link Content#canAddPrerequisite(ContentId, PrerequisiteCheckingRepository)}.
 *
 * <p>Concurrency: under §3.b.7.1 the writing use case opens a SERIALIZABLE
 * transaction so that a "no cycle" check followed by a "save" cannot race
 * with another writer creating the closing edge. The port itself does
 * not enforce this — the use case wraps the call sequence in
 * {@code @Transactional(isolation = SERIALIZABLE)}.
 *
 * <p>Traces to: §3.a (domain-owned ports), §3.c.1.3 (prerequisites
 * schema), §3.b.7.1 (SERIALIZABLE for cycle-write race), FR-09.
 */
public interface PrerequisiteRepository extends PrerequisiteCheckingRepository {

    /**
     * Persists an edge. Caller is expected to have validated via
     * {@link Content#canAddPrerequisite(ContentId, PrerequisiteCheckingRepository)}
     * already; this method does not re-check for cycles.
     *
     * <p>Throws {@link org.springframework.dao.DataIntegrityViolationException}
     * (unwrapped at this layer) when the edge already exists (composite
     * PK violation) or when the content_id refers to a row that was
     * deleted between the read and the write. The use-case layer wraps
     * those into domain outcomes.
     */
    PrerequisiteEdge save(PrerequisiteEdge edge);

    /** Removes the edge. Idempotent — removing a non-existent edge is a no-op. */
    void remove(ContentId contentId, ContentId prereqContentId);

    /** Lists every edge whose source is {@code contentId} (the things it requires). */
    List<PrerequisiteEdge> findDirectPrerequisitesOf(ContentId contentId);

    /** Lists every edge whose target is {@code contentId} (the things that require it). */
    List<PrerequisiteEdge> findDirectDependentsOf(ContentId contentId);

    boolean exists(ContentId contentId, ContentId prereqContentId);
}
