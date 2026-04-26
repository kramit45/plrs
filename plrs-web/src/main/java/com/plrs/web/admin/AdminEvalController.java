package com.plrs.web.admin;

import com.plrs.application.eval.EvalRun;
import com.plrs.application.eval.RunEvalUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only endpoint for triggering an offline evaluation run.
 *
 * <p>Routes through {@link RunEvalUseCase} which calls the ML
 * service and persists the resulting metrics to
 * {@code plrs_dw.fact_eval_run}. Returns the persisted record so
 * the admin UI can display the freshly-computed precision@k /
 * nDCG@k / coverage immediately.
 */
@RestController
@RequestMapping("/api/admin/eval")
@ConditionalOnProperty(name = {"spring.datasource.url", "plrs.ml.base-url"})
@Tag(name = "Admin · Offline Eval", description = "Trigger an offline recommender evaluation run (ADMIN)")
public class AdminEvalController {

    private final RunEvalUseCase useCase;

    public AdminEvalController(RunEvalUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping("/run")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Run an offline eval (ADMIN); persists to plrs_dw.fact_eval_run",
            description = "Calls plrs-ml /eval/run; returns the persisted record so the admin UI can render fresh metrics.")
    public EvalRunResponse run(
            @RequestParam(defaultValue = "hybrid_v1") String variant,
            @RequestParam(defaultValue = "10") int k) {
        EvalRun run = useCase.handle(variant, k);
        return EvalRunResponse.from(run);
    }
}
