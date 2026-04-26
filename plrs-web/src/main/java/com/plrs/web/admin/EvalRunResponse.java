package com.plrs.web.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.plrs.application.eval.EvalRun;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * REST DTO for an eval-run record. {@code Optional} fields collapse
 * to {@code null} (and Jackson omits them) so the JSON stays clean
 * for both OK and SKIPPED runs. {@code diversity} + {@code novelty}
 * were added by step 174 / V25 (NFR-35).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvalRunResponse(
        Long evalRunSk,
        Instant ranAt,
        String variant,
        short k,
        BigDecimal precisionAtK,
        BigDecimal ndcgAtK,
        BigDecimal coverage,
        BigDecimal diversity,
        BigDecimal novelty,
        Integer nUsers) {

    public static EvalRunResponse from(EvalRun run) {
        return new EvalRunResponse(
                run.evalRunSk().orElse(null),
                run.ranAt(),
                run.variantName(),
                run.k(),
                run.precisionAtK().orElse(null),
                run.ndcgAtK().orElse(null),
                run.coverage().orElse(null),
                run.diversity().orElse(null),
                run.novelty().orElse(null),
                run.nUsers().orElse(null));
    }
}
