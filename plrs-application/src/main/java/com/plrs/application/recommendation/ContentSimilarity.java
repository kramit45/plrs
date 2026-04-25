package com.plrs.application.recommendation;

import com.plrs.domain.content.ContentId;

/**
 * Pairwise content-content similarity in {@code [0, 1]}. Backed by the
 * TF-IDF cosine matrix produced by the build job (step 117) and read
 * through the infrastructure adapter; the MmrReranker (step 120) talks
 * to this port instead of the concrete reader so the application layer
 * stays framework-free.
 *
 * <p>Implementations must return {@code 0.0} for unknown ids rather
 * than throwing — MMR runs on best-effort signals and a missing row
 * should reduce diversity influence, not abort the request.
 */
public interface ContentSimilarity {

    /** Cosine similarity between two content rows in {@code [0, 1]}. */
    double cosine(ContentId a, ContentId b);
}
