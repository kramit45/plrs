package com.plrs.application.eval;

import com.plrs.application.recommendation.MlServiceClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Triggers an offline evaluation run on the ML service and persists
 * the result to {@code plrs_dw.fact_eval_run} (V17). The admin
 * endpoint (step 137) is the only caller in Iter 3.
 *
 * <p>Gated on {@code plrs.ml.base-url} so contexts without ML
 * configuration don't try to wire it.
 */
@Service
@ConditionalOnProperty(name = {"spring.datasource.url", "plrs.ml.base-url"})
public class RunEvalUseCase {

    private final MlServiceClient ml;
    private final EvalRunRepository repository;

    public RunEvalUseCase(MlServiceClient ml, EvalRunRepository repository) {
        this.ml = ml;
        this.repository = repository;
    }

    public EvalRun handle(String variant, int k) {
        EvalReport report = ml.runEval(variant, k);
        EvalRun fresh = EvalRun.fromReport(report);
        return repository.save(fresh);
    }
}
