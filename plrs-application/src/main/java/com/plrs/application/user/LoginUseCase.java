package com.plrs.application.user;

import com.plrs.application.security.IssuedTokens;
import com.plrs.application.security.PasswordEncoder;
import com.plrs.application.security.RefreshTokenStore;
import com.plrs.application.security.TokenService;
import com.plrs.domain.common.DomainValidationException;
import com.plrs.domain.user.BCryptHash;
import com.plrs.domain.user.Email;
import com.plrs.domain.user.User;
import com.plrs.domain.user.UserRepository;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Authenticates a user against email + password and mints the access +
 * refresh JWT pair.
 *
 * <p>The flow is deliberately <b>timing-safe</b>: an attacker probing the
 * login endpoint should not be able to tell whether an email is
 * registered by measuring response latency. Three branches that would
 * otherwise short-circuit still run a BCrypt {@code matches} against a
 * fixed dummy hash at the production cost factor so their wall-clock
 * time is comparable to the success path:
 *
 * <ul>
 *   <li>malformed email (fails {@link Email#of} validation),
 *   <li>unknown email (repository returns empty),
 *   <li>wrong password (real hash comparison runs regardless).
 * </ul>
 *
 * <p>The dummy hash is a real BCrypt-12 hash of a fixed, non-sensitive
 * string — produced offline, not stored in any runtime credential store
 * — so {@link PasswordEncoder#matches} performs the full 2^12 key
 * derivation regardless of which branch is active. Tests assert the
 * call is made via Mockito verification rather than wall-clock
 * measurements, which would be flaky on CI runners.
 *
 * <p>Only the success path issues tokens, persists the refresh jti to
 * the {@link RefreshTokenStore}, and returns a {@link LoginResult};
 * every failure path throws {@link InvalidCredentialsException} with a
 * single generic message that never echoes the attempted email.
 *
 * <p>Gated with {@code @ConditionalOnProperty(name = "spring.datasource.url")}
 * so the no-DB {@code PlrsApplicationTests} smoke test does not need the
 * {@link UserRepository} bean — same pattern as
 * {@link RegisterUserUseCase}.
 *
 * <p>Traces to: §2.c (login FR), §7 (JWT issuance, timing-safe auth).
 */
@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public class LoginUseCase {

    /**
     * A real BCrypt-12 hash of a fixed placeholder string, computed
     * offline. The value here has no relationship to any stored
     * credential; it exists only so the BCrypt matches() call on the
     * no-user path performs the same 2^12 key derivation the real path
     * does. Reused across every request — salt freshness does not matter
     * because we never store the result of the check.
     */
    static final String DUMMY_HASH_VALUE =
            "$2b$12$W/nGkbBGT3krr8rUkI2rE.eFKG/qcYOk.zjO/NCT9iv9qkv1uFYzO";

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final TokenService tokens;
    private final RefreshTokenStore refreshTokens;
    private final BCryptHash dummyHash;

    public LoginUseCase(
            UserRepository users,
            PasswordEncoder encoder,
            TokenService tokens,
            RefreshTokenStore refreshTokens) {
        this.users = users;
        this.encoder = encoder;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.dummyHash = BCryptHash.of(DUMMY_HASH_VALUE);
    }

    @com.plrs.application.audit.Auditable(action = "LOGIN_OK", entityType = "user")
    public LoginResult handle(LoginCommand cmd) {
        String rawPassword = cmd.rawPassword() == null ? "" : cmd.rawPassword();

        Email email;
        try {
            email = Email.of(cmd.email());
        } catch (DomainValidationException e) {
            encoder.matches(rawPassword, dummyHash);
            throw new InvalidCredentialsException();
        }

        Optional<User> maybeUser = users.findByEmail(email);
        User user = maybeUser.orElse(null);
        BCryptHash hashToCheck = user != null ? user.passwordHash() : dummyHash;
        boolean matches = encoder.matches(rawPassword, hashToCheck);
        if (user == null || !matches) {
            throw new InvalidCredentialsException();
        }

        IssuedTokens issued = tokens.issue(user.id(), user.roles());
        refreshTokens.store(issued.refreshJti(), user.id(), issued.refreshExpiresAt());
        return new LoginResult(
                user.id(),
                user.email(),
                user.roles(),
                issued.accessToken(),
                issued.refreshToken(),
                issued.accessExpiresAt(),
                issued.refreshExpiresAt());
    }
}
