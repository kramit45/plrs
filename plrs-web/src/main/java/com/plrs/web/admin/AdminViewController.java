package com.plrs.web.admin;

import com.plrs.application.eval.EvalRun;
import com.plrs.application.eval.EvalRunRepository;
import com.plrs.application.eval.RunEvalUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * Server-rendered admin home — surfaces the latest offline-eval run
 * and a button to trigger a fresh one.
 *
 * <p>Mounted on the form-login chain (no {@code /api} prefix) so the
 * "Run new evaluation" form POST rides the existing session cookie +
 * CSRF token already attached to the page; no JWT roundtrip
 * required.
 *
 * <p>Spec deviation: the spec proposed a fetch-and-CSRF dance against
 * {@code /api/admin/eval/run} (which lives on the JWT-only API
 * chain), which would require either a separate token bridge or
 * the POST endpoint mirrored under {@code /web-api}. A plain form
 * POST + Post-Redirect-Get to {@code /admin} is simpler, more
 * robust, and viva-friendly. The original
 * {@link AdminEvalController} (under {@code /api}) is kept for
 * Newman / programmatic callers.
 */
@Controller
@ConditionalOnProperty(name = {"spring.datasource.url", "plrs.ml.base-url"})
public class AdminViewController {

    private final RunEvalUseCase runEvalUseCase;
    private final EvalRunRepository evalRunRepository;

    public AdminViewController(
            RunEvalUseCase runEvalUseCase, EvalRunRepository evalRunRepository) {
        this.runEvalUseCase = runEvalUseCase;
        this.evalRunRepository = evalRunRepository;
    }

    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public String adminHome(Model model) {
        EvalRun latest = evalRunRepository.findLatest().orElse(null);
        model.addAttribute(
                "latestEval", latest == null ? null : EvalRunResponse.from(latest));
        model.addAttribute("pageTitle", "Admin — PLRS");
        return "admin/home";
    }

    @PostMapping("/admin/eval/run")
    @PreAuthorize("hasRole('ADMIN')")
    public String triggerEval() {
        runEvalUseCase.handle("hybrid_v1", 10);
        return "redirect:/admin";
    }
}
