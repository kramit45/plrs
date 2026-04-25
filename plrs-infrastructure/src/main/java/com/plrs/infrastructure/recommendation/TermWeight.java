package com.plrs.infrastructure.recommendation;

/**
 * One sparse cell of a TF-IDF row: an index into the shared vocabulary
 * plus the L2-normalised weight at that term. The {@link TfIdfReader}
 * exposes rows as {@code List<TermWeight>}.
 */
public record TermWeight(int idx, double weight) {}
