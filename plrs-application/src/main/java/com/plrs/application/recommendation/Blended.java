package com.plrs.application.recommendation;

/**
 * Per-candidate blended-score record returned by {@link HybridRanker}.
 *
 * <p>{@code coldStart} flips when the user's CF and CB signals are
 * both effectively absent — in that case the ranker substitutes the
 * popularity score for the formula output (FR-30 fallback).
 */
public record Blended(
        double score,
        double cf,
        double cb,
        double popularity,
        boolean coldStart) {}
