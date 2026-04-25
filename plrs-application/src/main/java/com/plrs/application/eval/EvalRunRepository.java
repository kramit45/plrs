package com.plrs.application.eval;

import java.util.Optional;

/**
 * Application-layer port for {@code plrs_dw.fact_eval_run}.
 *
 * <p>Two methods only — admin code persists a fresh run and reads
 * the latest one for the dashboard tile (step 137). Listing /
 * trending / time-series queries land later when the dashboard
 * grows.
 */
public interface EvalRunRepository {

    /** Persists a new row and returns it with {@code evalRunSk} populated. */
    EvalRun save(EvalRun run);

    /** Returns the most recent run by {@code ranAt}, if any. */
    Optional<EvalRun> findLatest();
}
