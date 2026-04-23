package com.plrs.infrastructure.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.plrs.application.security.InvalidTokenException;
import com.plrs.application.security.IssuedTokens;
import com.plrs.application.security.TokenClaims;
import com.plrs.domain.user.Role;
import com.plrs.domain.user.UserId;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class JjwtTokenServiceTest {

    private static final Instant T0 = Instant.parse("2026-04-23T10:00:00Z");
    private static final Clock FIXED = Clock.fixed(T0, ZoneOffset.UTC);

    private static KeyPair keypairA;
    private static KeyPair keypairB;

    @BeforeAll
    static void generateKeypairs() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keypairA = generator.generateKeyPair();
        keypairB = generator.generateKeyPair();
    }

    private static String pem(byte[] encoded, String label) {
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded);
        return "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----";
    }

    private static JwtProperties defaultProperties() {
        return propertiesFor(keypairA, "plrs", "plrs-web");
    }

    private static JwtProperties propertiesFor(KeyPair keypair, String issuer, String audience) {
        return new JwtProperties(
                Duration.ofHours(2),
                Duration.ofDays(30),
                issuer,
                audience,
                pem(keypair.getPrivate().getEncoded(), "PRIVATE KEY"),
                pem(keypair.getPublic().getEncoded(), "PUBLIC KEY"),
                false);
    }

    private static JjwtTokenService newService(JwtProperties properties, Clock clock) {
        return new JjwtTokenService(new JwtKeyProvider(properties), properties, clock);
    }

    @Test
    void issueThenVerifyAccessRoundTripsSubjectAndRoles() {
        JjwtTokenService service = newService(defaultProperties(), FIXED);
        UserId userId = UserId.newId();
        Set<Role> roles = Set.of(Role.STUDENT, Role.INSTRUCTOR);

        IssuedTokens tokens = service.issue(userId, roles);
        TokenClaims claims = service.verifyAccess(tokens.accessToken());

        assertThat(claims.subject()).isEqualTo(userId);
        assertThat(claims.roles()).containsExactlyInAnyOrder(Role.STUDENT, Role.INSTRUCTOR);
        assertThat(claims.expiresAt()).isEqualTo(T0.plus(Duration.ofHours(2)));
    }

    @Test
    void issueThenVerifyRefreshRoundTripsSubjectAndJti() {
        JjwtTokenService service = newService(defaultProperties(), FIXED);
        UserId userId = UserId.newId();

        IssuedTokens tokens = service.issue(userId, Set.of(Role.STUDENT));
        TokenClaims claims = service.verifyRefresh(tokens.refreshToken());

        assertThat(claims.subject()).isEqualTo(userId);
        assertThat(claims.jti()).isEqualTo(tokens.refreshJti());
        assertThat(claims.expiresAt()).isEqualTo(tokens.refreshExpiresAt());
    }

    @Test
    void verifyAccessRejectsRefreshToken() {
        JjwtTokenService service = newService(defaultProperties(), FIXED);
        IssuedTokens tokens = service.issue(UserId.newId(), Set.of(Role.STUDENT));

        assertThatThrownBy(() -> service.verifyAccess(tokens.refreshToken()))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("typ mismatch");
    }

    @Test
    void verifyRefreshRejectsAccessToken() {
        JjwtTokenService service = newService(defaultProperties(), FIXED);
        IssuedTokens tokens = service.issue(UserId.newId(), Set.of(Role.STUDENT));

        assertThatThrownBy(() -> service.verifyRefresh(tokens.accessToken()))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("typ mismatch");
    }

    @Test
    void verifyAccessRejectsExpiredToken() {
        JwtProperties properties = defaultProperties();
        JjwtTokenService issuer = newService(properties, FIXED);
        IssuedTokens tokens = issuer.issue(UserId.newId(), Set.of(Role.STUDENT));

        Clock future = Clock.fixed(T0.plus(Duration.ofHours(3)), ZoneOffset.UTC);
        JjwtTokenService verifier =
                new JjwtTokenService(new JwtKeyProvider(properties), properties, future);

        assertThatThrownBy(() -> verifier.verifyAccess(tokens.accessToken()))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void verifyAccessRejectsTokenWithWrongIssuer() {
        JwtProperties foreign = propertiesFor(keypairA, "evil", "plrs-web");
        JjwtTokenService foreignIssuer = newService(foreign, FIXED);
        IssuedTokens tokens = foreignIssuer.issue(UserId.newId(), Set.of(Role.STUDENT));

        JjwtTokenService honest = newService(defaultProperties(), FIXED);

        assertThatThrownBy(() -> honest.verifyAccess(tokens.accessToken()))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void verifyAccessRejectsTokenSignedByDifferentKey() {
        JwtProperties attackerProps = propertiesFor(keypairB, "plrs", "plrs-web");
        JjwtTokenService attacker = newService(attackerProps, FIXED);
        IssuedTokens tokens = attacker.issue(UserId.newId(), Set.of(Role.STUDENT));

        JjwtTokenService honest = newService(defaultProperties(), FIXED);

        assertThatThrownBy(() -> honest.verifyAccess(tokens.accessToken()))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void verifyAccessRejectsTamperedToken() {
        JjwtTokenService service = newService(defaultProperties(), FIXED);
        IssuedTokens tokens = service.issue(UserId.newId(), Set.of(Role.STUDENT));

        String tampered = flipLastSignatureChar(tokens.accessToken());

        assertThatThrownBy(() -> service.verifyAccess(tampered))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void issuedTokensAreSignedWithRs256() {
        JwtProperties properties = defaultProperties();
        JjwtTokenService service = newService(properties, FIXED);
        IssuedTokens tokens = service.issue(UserId.newId(), Set.of(Role.STUDENT));

        Jws<io.jsonwebtoken.Claims> jws =
                Jwts.parser()
                        .verifyWith(new JwtKeyProvider(properties).publicKey())
                        .clock(() -> java.util.Date.from(T0))
                        .build()
                        .parseSignedClaims(tokens.accessToken());

        assertThat(jws.getHeader().getAlgorithm()).isEqualTo("RS256");
        assertThat(jws.getHeader().getKeyId()).isNotBlank();
    }

    private static String flipLastSignatureChar(String token) {
        // Flip the first char of the signature segment rather than the last.
        // The final base64url char encodes padding bits that may not map to
        // any signature byte, so A↔B there can leave the decoded signature
        // unchanged — flakily passing verification. The first signature
        // char is always fully meaningful.
        int dot = token.lastIndexOf('.');
        char c = token.charAt(dot + 1);
        char replacement = c == 'A' ? 'B' : 'A';
        return token.substring(0, dot + 1) + replacement + token.substring(dot + 2);
    }
}
