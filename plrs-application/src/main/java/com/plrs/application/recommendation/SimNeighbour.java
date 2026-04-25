package com.plrs.application.recommendation;

/**
 * One row of an item's similarity slab as the application layer sees
 * it. Mirrors the JSON shape the {@code ItemSimilarityJob} (step 111)
 * writes to Redis at {@code sim:item:{contentId}}.
 */
public record SimNeighbour(long contentId, double similarity) {}
