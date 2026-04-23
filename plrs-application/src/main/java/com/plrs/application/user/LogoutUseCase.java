package com.plrs.application.user;

import com.plrs.application.security.InvalidTokenException;
import com.plrs.application.security.RefreshTokenStore;
import com.plrs.application.security.TokenClaims;
import com.plrs.application.security.TokenService;
import org.springframework.stereotype.Service;

/**
 * Revokes a refresh token so it can no longer be used to mint a new
 * access token. The flow is minimal and deliberately does not consult
 * the {@code UserRepository}: a signed refresh JWT carries its own
 * proof of provenance, and the store's jti→userId mapping already pins
 * the token to a principal — re-reading the user record would add a
 * round-trip with no security benefit.
 *
 * <p>The operation is <b>idempotent</b> in two senses:
 *
 * <ul>
 *   <li>{@link RefreshTokenStore#revoke(String)} is a no-op on unknown
 *       jtis, so a second logout from the same client (or a logout of an
 *       already-revoked token whose JWT signature is still valid) both
 *       succeed silently.
 *   <li>The allow-list check happens at <em>refresh</em> time (step 40),
 *       not at logout — so a token that verifies here may still be absent
 *       from Redis without changing logout's behaviour.
 * </ul>
 *
 * <p>Tokens that fail JWT verification (bad signature, expired, wrong
 * issuer, tampered, wrong typ) propagate as {@link InvalidTokenException}
 * from {@link TokenService#verifyRefresh(String)}; the web layer maps
 * this to HTTP 401.
 *
 * <p>Traces to: §7 (refresh-token allow-list revocation on logout).
 */
@Service
public class LogoutUseCase {

    private final TokenService tokens;
    private final RefreshTokenStore refreshTokens;

    public LogoutUseCase(TokenService tokens, RefreshTokenStore refreshTokens) {
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
    }

    public void handle(LogoutCommand cmd) {
        if (cmd.refreshToken() == null || cmd.refreshToken().isBlank()) {
            throw new InvalidTokenException("Refresh token is required");
        }
        TokenClaims claims = tokens.verifyRefresh(cmd.refreshToken());
        refreshTokens.revoke(claims.jti());
    }
}
