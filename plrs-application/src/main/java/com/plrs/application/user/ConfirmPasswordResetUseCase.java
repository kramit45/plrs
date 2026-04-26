package com.plrs.application.user;

import com.plrs.application.audit.Auditable;
import com.plrs.application.security.PasswordEncoder;
import com.plrs.application.security.RefreshTokenStore;
import com.plrs.domain.user.PasswordPolicy;
import com.plrs.domain.user.User;
import com.plrs.domain.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-04: validates a reset token + new password, writes the new
 * password hash, clears the token, and revokes every refresh JTI for
 * the user (so old sessions can't survive the password change).
 *
 * <p>Throws {@link InvalidResetTokenException} when the token is
 * unknown OR expired — the response shape is identical so an attacker
 * cannot distinguish the two.
 *
 * <p>Traces to: FR-04.
 */
@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public class ConfirmPasswordResetUseCase {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final RefreshTokenStore refreshTokens;
    private final Clock clock;

    public ConfirmPasswordResetUseCase(
            UserRepository users,
            PasswordEncoder encoder,
            RefreshTokenStore refreshTokens,
            Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.refreshTokens = refreshTokens;
        this.clock = clock;
    }

    @Transactional
    @Auditable(action = "PASSWORD_RESET_COMPLETED", entityType = "user")
    public void handle(String token, String newPassword) {
        if (token == null || token.isBlank()) {
            throw new InvalidResetTokenException();
        }
        Optional<User> maybeUser = users.findByResetToken(token);
        if (maybeUser.isEmpty()) {
            throw new InvalidResetTokenException();
        }
        User user = maybeUser.get();
        Optional<Instant> expiresAt = users.getResetExpiresAt(user.id());
        if (expiresAt.isEmpty() || expiresAt.get().isBefore(Instant.now(clock))) {
            throw new InvalidResetTokenException();
        }

        // Domain policy check on the new password (raises
        // DomainValidationException → 400 via global handler).
        PasswordPolicy.validate(newPassword);

        String newHash = encoder.encode(newPassword).value();
        users.updatePasswordHash(user.id(), newHash);
        users.clearResetToken(user.id());

        // Revoke every active refresh token so the previous session
        // can't outlive the password change.
        refreshTokens.revokeAllForUser(user.id());
    }
}
