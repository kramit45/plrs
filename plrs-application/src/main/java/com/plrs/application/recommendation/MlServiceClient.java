package com.plrs.application.recommendation;

import com.plrs.application.eval.EvalReport;
import com.plrs.domain.content.ContentId;
import java.util.List;

/**
 * Client port for the Python ML microservice (EIR-09).
 *
 * <p>Read endpoints ({@link #cfSimilar}, {@link #cbSimilar}) feed the
 * composite scorers in step 130; write endpoints
 * ({@link #rebuildFeatures}, {@link #recomputeCf}) trigger the
 * offline TF-IDF and item-item-CF rebuilds. {@link #isReachable}
 * is the cheap probe the recommender uses to choose between the ML
 * path and the in-process fallback.
 *
 * <p>All methods throw {@link MlServiceException} on transport
 * failure or 5xx after the retry budget is exhausted; callers
 * decide whether to surface the failure or degrade gracefully.
 */
public interface MlServiceClient {

    /** Top-k item-item CF neighbours for {@code itemId}. */
    List<SimNeighbour> cfSimilar(ContentId itemId, int k);

    /** Top-k content-similar items for {@code itemId}. */
    List<SimNeighbour> cbSimilar(ContentId itemId, int k);

    /** Triggers the TF-IDF rebuild on the ML side. */
    RebuildResult rebuildFeatures();

    /** Triggers the item-item CF recompute on the ML side. */
    RebuildResult recomputeCf();

    /** Runs the offline evaluation harness and returns the metrics report. */
    EvalReport runEval(String variant, int k);

    /** Cheap liveness probe — true iff {@code GET /health} returns 200. */
    boolean isReachable();
}
