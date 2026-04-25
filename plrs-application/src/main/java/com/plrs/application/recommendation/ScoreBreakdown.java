package com.plrs.application.recommendation;

/**
 * Per-component score for one recommendation, surfaced to ADMIN
 * callers as a debugging payload. Students don't see this — the slim
 * {@code RecommendationResponse} stays score-only for them.
 *
 * <p>{@code blended} is the value that drove the rank; the three
 * component scores are kept around so an admin can see why an item
 * ranked where it did. {@code cb} is a placeholder zero until step
 * 118 wires the CB scorer.
 */
public record ScoreBreakdown(double popularity, double cf, double cb, double blended) {}
