package com.plrs.application.recommendation;

import com.plrs.domain.content.ContentId;
import com.plrs.domain.user.UserId;
import java.util.Map;
import java.util.Set;

/**
 * Content-based scorer. For a user and a candidate content set,
 * returns a per-candidate score in {@code [0, 1]} computed as the
 * cosine similarity between the candidate's TF-IDF row and the user's
 * "interest centroid" — the L2-normalised mean of the TF-IDF rows for
 * their recently completed items.
 *
 * <p>Step 119's HybridRanker blends this with the CF score under
 * {@code λ_blend} per FR-25.
 *
 * <p>Traces to: §3.c.7 (CbScorer), §3.c.5.2 (A2).
 */
public interface CbScorer {

    /** Returns a per-candidate score in {@code [0, 1]}. */
    Map<ContentId, Double> score(UserId userId, Set<ContentId> candidates);
}
