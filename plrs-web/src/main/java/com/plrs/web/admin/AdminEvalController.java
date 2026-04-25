package com.plrs.web.admin;

import com.plrs.application.eval.EvalRun;
import com.plrs.application.eval.RunEvalUseCase;
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
public class AdminEvalController {

    private final RunEvalUseCase useCase;

    public AdminEvalController(RunEvalUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping("/run")
    @PreAuthorize("hasRole('ADMIN')")
    public EvalRunResponse run(
            @RequestParam(defaultValue = "hybrid_v1") String variant,
            @RequestParam(defaultValue = "10") int k) {
        EvalRun run = useCase.handle(variant, k);
        return EvalRunResponse.from(run);
    }
}
