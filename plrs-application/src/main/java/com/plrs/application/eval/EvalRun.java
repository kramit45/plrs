package com.plrs.application.eval;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * Persisted {@code plrs_dw.fact_eval_run} row.
 *
 * <p>{@code evalRunSk} is empty for a freshly-built record and present
 * after the repository assigns it. Metric fields are {@code Optional}
 * because the schema columns are nullable — a SKIPPED ML run leaves
 * them blank.
 */
public record EvalRun(
        Optional<Long> evalRunSk,
        Instant ranAt,
        String variantName,
        short k,
        Optional<BigDecimal> precisionAtK,
        Optional<BigDecimal> ndcgAtK,
        Optional<BigDecimal> coverage,
        Optional<Integer> nUsers) {

    /** Builds an unpersisted row from an {@link EvalReport}. */
    public static EvalRun fromReport(EvalReport report) {
        return new EvalRun(
                Optional.empty(),
                report.ranAt(),
                report.variant(),
                (short) report.k(),
                report.precisionAtK().map(BigDecimal::valueOf),
                report.ndcgAtK().map(BigDecimal::valueOf),
                report.coverage().map(BigDecimal::valueOf),
                report.nUsers());
    }
}
