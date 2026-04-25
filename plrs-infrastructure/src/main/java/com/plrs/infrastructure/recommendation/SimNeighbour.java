package com.plrs.infrastructure.recommendation;

/**
 * One row of an item's similarity slab: a (neighbour content id,
 * cosine similarity) pair. Used by both the Iter 3
 * {@link ItemSimilarityJob} writer and the
 * {@code application.recommendation.RedisCfScorer} reader (step 113).
 *
 * <p>Lives in {@code plrs-infrastructure} because the slab format is
 * an implementation detail of how the cosine matrix is materialised.
 */
public record SimNeighbour(long contentId, double similarity) {}
