package com.plrs.web.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * Form-binding model for the Thymeleaf registration view. Kept distinct
 * from {@link RegisterRequest} — the JSON API record — because the form
 * flow has a different validation story: Spring must be able to construct
 * the record with empty strings when the template first renders, so no
 * validation runs inside the canonical constructor. The {@link NotBlank}
 * annotations are enforced by the controller via {@code @Valid}.
 */
public record RegisterFormModel(@NotBlank String email, @NotBlank String password) {}
