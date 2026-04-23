package com.plrs.web.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * JSON logout payload. The client submits the refresh token (not the
 * access token) because revocation targets the long-lived credential;
 * the access token expires quickly on its own and has no server-side
 * entry to remove.
 */
public record LogoutRequest(@NotBlank String refreshToken) {}
