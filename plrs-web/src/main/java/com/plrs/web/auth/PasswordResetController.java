package com.plrs.web.auth;

import com.plrs.application.user.ConfirmPasswordResetUseCase;
import com.plrs.application.user.RequestPasswordResetUseCase;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FR-04 password-reset endpoints. Both return 204 on success — no
 * data leaks about email existence (request) or token validity until
 * the body itself is invalid (confirm). The IP rate limit (step 154)
 * already covers the parent {@code /api/auth/login} path; the reset
 * endpoints share the same base prefix and inherit similar
 * protection from the global IP limiter once that's broadened
 * (deferred per spec note).
 *
 * <p>Traces to: FR-04.
 */
@RestController
@RequestMapping("/api/auth/password-reset")
@ConditionalOnProperty(name = "spring.datasource.url")
public class PasswordResetController {

    private final RequestPasswordResetUseCase requestUseCase;
    private final ConfirmPasswordResetUseCase confirmUseCase;

    public PasswordResetController(
            RequestPasswordResetUseCase requestUseCase,
            ConfirmPasswordResetUseCase confirmUseCase) {
        this.requestUseCase = requestUseCase;
        this.confirmUseCase = confirmUseCase;
    }

    @PostMapping("/request")
    public ResponseEntity<Void> request(@RequestBody PasswordResetRequest req) {
        // 204 in BOTH the success and unknown-email cases so an
        // attacker cannot enumerate registered emails.
        requestUseCase.handle(req.email());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/confirm")
    public ResponseEntity<Void> confirm(@RequestBody PasswordResetConfirm req) {
        confirmUseCase.handle(req.token(), req.newPassword());
        return ResponseEntity.noContent().build();
    }

    public record PasswordResetRequest(@NotBlank String email) {}

    public record PasswordResetConfirm(@NotBlank String token, @NotBlank String newPassword) {}
}
