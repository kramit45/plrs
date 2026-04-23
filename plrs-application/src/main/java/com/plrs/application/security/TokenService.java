package com.plrs.application.security;

import com.plrs.domain.user.Role;
import com.plrs.domain.user.UserId;
import java.util.Set;

/**
 * Application-owned port for JWT issuance and verification. The port is
 * deliberately narrow — one issue, two verify variants — so swapping the
 * underlying library (today JJWT) requires only an adapter change, and the
 * access/refresh distinction is encoded in the API rather than in
 * configuration.
 *
 * <p>Issuing returns a structured {@link IssuedTokens} so callers that need
 * to persist refresh-token metadata (for example the allow-list in step 33)
 * don't have to re-parse the token to discover its jti and expiry.
 * Verification returns a domain-typed {@link TokenClaims}; signature,
 * issuer, audience, expiry, and typ checks all surface uniformly as
 * {@link InvalidTokenException}.
 *
 * <p>Traces to: §7 (JWT RS256: 2h access, 30d refresh; jti for refresh).
 */
public interface TokenService {

    /**
     * Issues a fresh access + refresh pair for the given principal.
     */
    IssuedTokens issue(UserId userId, Set<Role> roles);

    /**
     * Verifies and parses an access token.
     *
     * @throws InvalidTokenException when the token is unsigned, signed by a
     *     different key, has a wrong issuer/audience, has expired, is
     *     tampered, or has {@code typ != "access"}
     */
    TokenClaims verifyAccess(String token);

    /**
     * Verifies and parses a refresh token. Same failure contract as
     * {@link #verifyAccess(String)} except the required {@code typ} is
     * {@code "refresh"}.
     */
    TokenClaims verifyRefresh(String token);
}
