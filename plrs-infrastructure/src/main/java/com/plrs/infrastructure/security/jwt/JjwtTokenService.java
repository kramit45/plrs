package com.plrs.infrastructure.security.jwt;

import com.plrs.application.security.InvalidTokenException;
import com.plrs.application.security.IssuedTokens;
import com.plrs.application.security.TokenClaims;
import com.plrs.application.security.TokenService;
import com.plrs.domain.user.Role;
import com.plrs.domain.user.UserId;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * JJWT-backed implementation of {@link TokenService}. All tokens are
 * signed RS256 using the keypair produced by {@link JwtKeyProvider}; the
 * cost of a symmetric HS256 shortcut would be losing the ability to
 * distribute the public key for verification by other services, so the
 * algorithm is pinned in code rather than exposed as a property.
 *
 * <p>Issue writes two tokens:
 *
 * <ul>
 *   <li>an <em>access</em> token carrying {@code sub}, {@code roles},
 *       {@code typ=access}, and short-lived ({@link JwtProperties#accessTokenTtl}),
 *   <li>a <em>refresh</em> token carrying {@code sub}, {@code typ=refresh},
 *       a random {@code jti}, and long-lived
 *       ({@link JwtProperties#refreshTokenTtl}) — the jti is surfaced on
 *       {@link IssuedTokens} so step 33's allow-list can persist it.
 * </ul>
 *
 * <p>Verify is a single shared helper with a {@code typ} check: the access
 * verifier will not accept a refresh token and vice versa. Any JJWT
 * failure (bad signature, expired, wrong issuer, tampered) is caught and
 * re-thrown as {@link InvalidTokenException} so the application layer has
 * one exception type to handle.
 *
 * <p>Traces to: §7 (JWT RS256: 2h access, 30d refresh; jti for refresh).
 */
@Component
public final class JjwtTokenService implements TokenService {

    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_TYP = "typ";
    private static final String TYP_ACCESS = "access";
    private static final String TYP_REFRESH = "refresh";

    private final JwtKeyProvider keyProvider;
    private final JwtProperties properties;
    private final Clock clock;

    public JjwtTokenService(JwtKeyProvider keyProvider, JwtProperties properties, Clock clock) {
        this.keyProvider = keyProvider;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public IssuedTokens issue(UserId userId, Set<Role> roles) {
        Instant now = Instant.now(clock);
        Instant accessExp = now.plus(properties.accessTokenTtl());
        Instant refreshExp = now.plus(properties.refreshTokenTtl());
        String refreshJti = UUID.randomUUID().toString();
        List<String> roleNames = roles.stream().map(Enum::name).toList();

        String access =
                Jwts.builder()
                        .issuer(properties.issuer())
                        .audience()
                        .add(properties.audience())
                        .and()
                        .subject(userId.value().toString())
                        .issuedAt(Date.from(now))
                        .expiration(Date.from(accessExp))
                        .claim(CLAIM_ROLES, roleNames)
                        .claim(CLAIM_TYP, TYP_ACCESS)
                        .header()
                        .keyId(keyProvider.keyId())
                        .and()
                        .signWith(keyProvider.privateKey(), Jwts.SIG.RS256)
                        .compact();

        String refresh =
                Jwts.builder()
                        .issuer(properties.issuer())
                        .audience()
                        .add(properties.audience())
                        .and()
                        .subject(userId.value().toString())
                        .id(refreshJti)
                        .issuedAt(Date.from(now))
                        .expiration(Date.from(refreshExp))
                        .claim(CLAIM_TYP, TYP_REFRESH)
                        .header()
                        .keyId(keyProvider.keyId())
                        .and()
                        .signWith(keyProvider.privateKey(), Jwts.SIG.RS256)
                        .compact();

        return new IssuedTokens(access, refresh, refreshJti, refreshExp);
    }

    @Override
    public TokenClaims verifyAccess(String token) {
        return verify(token, TYP_ACCESS);
    }

    @Override
    public TokenClaims verifyRefresh(String token) {
        return verify(token, TYP_REFRESH);
    }

    private TokenClaims verify(String token, String expectedTyp) {
        Claims claims;
        try {
            Jws<Claims> jws =
                    Jwts.parser()
                            .verifyWith(keyProvider.publicKey())
                            .requireIssuer(properties.issuer())
                            .requireAudience(properties.audience())
                            .clock(() -> Date.from(Instant.now(clock)))
                            .build()
                            .parseSignedClaims(token);
            claims = jws.getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException("token verification failed: " + e.getMessage(), e);
        }

        String typ = claims.get(CLAIM_TYP, String.class);
        if (!expectedTyp.equals(typ)) {
            throw new InvalidTokenException(
                    "token typ mismatch: expected " + expectedTyp + ", got " + typ);
        }

        UserId subject;
        try {
            subject = UserId.of(claims.getSubject());
        } catch (RuntimeException e) {
            throw new InvalidTokenException("token subject is not a valid UserId", e);
        }

        Set<Role> roles = rolesFromClaim(claims);
        Instant expiresAt = claims.getExpiration().toInstant();
        return new TokenClaims(subject, roles, claims.getId(), expiresAt);
    }

    private static Set<Role> rolesFromClaim(Claims claims) {
        Object raw = claims.get(CLAIM_ROLES);
        if (raw == null) {
            return Set.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new InvalidTokenException("roles claim is not a list");
        }
        try {
            return list.stream()
                    .map(Object::toString)
                    .map(Role::fromName)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (RuntimeException e) {
            throw new InvalidTokenException("roles claim contains an unknown role", e);
        }
    }
}
