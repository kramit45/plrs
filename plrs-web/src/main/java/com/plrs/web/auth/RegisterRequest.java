package com.plrs.web.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * Registration payload. The two annotations fire before the controller
 * runs and produce {@link org.springframework.web.bind.MethodArgumentNotValidException}
 * on failure; the domain-level {@link com.plrs.domain.user.Email} and
 * {@link com.plrs.domain.user.PasswordPolicy} checks run inside the use
 * case and surface as
 * {@link com.plrs.domain.common.DomainValidationException}. Both map to
 * HTTP 400 via the global exception handler — the layered validation is
 * intentional: framework-level checks catch missing fields cheaply, the
 * domain layer owns the semantic rules.
 */
public record RegisterRequest(@NotBlank String email, @NotBlank String password) {}
