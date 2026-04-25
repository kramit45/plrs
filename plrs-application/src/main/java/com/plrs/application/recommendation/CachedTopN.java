package com.plrs.application.recommendation;

import com.plrs.domain.recommendation.Recommendation;
import java.time.Instant;
import java.util.List;

/**
 * Cached top-N recommendation slate for one user, stamped with the
 * {@code users.user_skills_version} value at compute time. The cache
 * read in {@link GenerateRecommendationsUseCase} compares
 * {@link #version} against the user's current
 * {@code user_skills_version} — a mismatch invalidates the entry and
 * forces a recompute (§2.e.2.3.3).
 *
 * <p>{@code computedAt} is informational; the TTL is enforced by
 * Redis itself, not by reading this field.
 */
public record CachedTopN(long version, List<Recommendation> items, Instant computedAt) {}
