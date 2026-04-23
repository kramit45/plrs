package com.plrs.web.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * JSON login payload. {@code @NotBlank} catches obviously-empty fields at
 * the controller boundary — the full credential validation happens inside
 * {@link com.plrs.application.user.LoginUseCase}, which deliberately
 * produces indistinguishable errors for "email missing from DB" and
 * "password wrong" so attackers cannot enumerate registered addresses.
 */
public record LoginRequest(@NotBlank String email, @NotBlank String password) {}
