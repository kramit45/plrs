package com.plrs.application.user;

/**
 * Input to {@link LogoutUseCase}. Carries the refresh token (not the
 * access token) because revocation targets the long-lived credential; the
 * access token expires quickly on its own and has no server-side
 * representation to remove.
 */
public record LogoutCommand(String refreshToken) {}
