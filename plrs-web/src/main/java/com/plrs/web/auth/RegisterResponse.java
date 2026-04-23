package com.plrs.web.auth;

import java.util.UUID;

/**
 * Body returned on successful registration. The email is echoed in its
 * normalised form (lowercase, trimmed) so the client can reconcile the
 * canonical value it should use for subsequent login requests.
 */
public record RegisterResponse(UUID userId, String email) {}
