package com.plrs.application.recommendation;

import com.plrs.domain.content.ContentId;
import com.plrs.domain.user.UserId;
import java.util.Map;
import java.util.Set;

/**
 * Collaborative-filtering scorer. For a user and a candidate content
 * set, returns a per-candidate score in {@code [0, 1]} computed as
 * the average similarity between the candidate and the user's recent
 * positive interactions (read out of the
 * {@code sim:item:{contentId}} slabs the {@code ItemSimilarityJob}
 * (step 111) maintains, with the {@code model_artifacts} table as
 * the cold-start fallback).
 *
 * <p>Traces to: §3.c.7 (CfScorer), §3.c.5.3 (A3).
 */
public interface CfScorer {

    /** Returns a per-candidate score in {@code [0, 1]}. */
    Map<ContentId, Double> score(UserId userId, Set<ContentId> candidates);
}
