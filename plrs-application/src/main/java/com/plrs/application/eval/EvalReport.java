package com.plrs.application.eval;

import java.time.Instant;
import java.util.Optional;

/**
 * One eval-run report returned by the ML service's POST /eval/run.
 * Mirrors the JSON the FastAPI endpoint returns: {@code status} is
 * either {@code OK} (metrics populated) or {@code SKIPPED} (insufficient
 * data; metrics are empty and {@code reason} carries the explanation).
 *
 * <p>{@code diversity} + {@code novelty} were added in step 174
 * (NFR-35 bias guardrails). Older ML services that don't return these
 * fields are tolerated — the parser collapses missing keys to
 * {@link Optional#empty()}.
 */
public record EvalReport(
        String status,
        String variant,
        int k,
        Optional<Double> precisionAtK,
        Optional<Double> ndcgAtK,
        Optional<Double> coverage,
        Optional<Double> diversity,
        Optional<Double> novelty,
        Optional<Integer> nUsers,
        Instant ranAt,
        Optional<String> reason) {

    public boolean isOk() {
        return "OK".equals(status);
    }
}
