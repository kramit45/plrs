package com.plrs.application.recommendation;

import com.plrs.domain.content.ContentId;
import com.plrs.domain.recommendation.Recommendation;
import java.util.List;
import java.util.Map;

/**
 * Internal recommender result that pairs the persisted-shape
 * {@link Recommendation} list with the per-content
 * {@link ScoreBreakdown}. The breakdown is a transient debugging
 * payload — never persisted, never cached, only surfaced through the
 * ADMIN path on the recommendation API (step 116).
 */
public record RankedSlate(
        List<Recommendation> items, Map<ContentId, ScoreBreakdown> breakdowns) {}
