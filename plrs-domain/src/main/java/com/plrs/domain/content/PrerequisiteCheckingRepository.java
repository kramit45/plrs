package com.plrs.domain.content;

import java.util.List;

/**
 * Narrow domain port for cycle detection on the prerequisite DAG. Kept
 * separate from {@link ContentRepository} so that the {@link Content}
 * aggregate can depend on this single capability without pulling in all
 * of {@code ContentRepository}'s surface — the "interface segregation"
 * variant of the "repository passed in" pattern (§3.b.2.3).
 *
 * <p>Implementations walk the existing prerequisite graph (typically via a
 * recursive SQL CTE in the adapter, step 62) and return the path that
 * would close a cycle if the candidate edge were added. Returning the
 * full path — rather than just a boolean — lets the caller surface
 * actionable diagnostics ("would close a cycle via A → B → C → A").
 *
 * <p>Traces to: §3.b.2.3 (no-cycle invariant; "repo passed in" pattern),
 * FR-09 (prerequisite tracking).
 */
public interface PrerequisiteCheckingRepository {

    /**
     * Returns the cycle path that would form if the edge
     * {@code (content, prereq)} were added. An empty list means the edge
     * is safe to add. The path, when non-empty, starts and ends at the
     * same node so the caller can render a complete loop.
     *
     * @param content the would-be edge's source ({@code C} in C → P)
     * @param prereq the would-be edge's target ({@code P} in C → P)
     * @return the cycle path, or an empty list if no cycle would form
     */
    List<ContentId> findCyclePath(ContentId content, ContentId prereq);
}
