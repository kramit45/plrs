package com.plrs.application.user;

import com.plrs.application.audit.Auditable;
import com.plrs.domain.user.Email;
import com.plrs.domain.user.User;
import com.plrs.domain.user.UserRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-04 (minimum): mints a 32-char URL-safe random token, stores it
 * with a 30-min expiry on the user row, and returns it. In production
 * this would be emailed; for the demo the token is logged at INFO so
 * the admin can copy it from the console.
 *
 * <p>Returns {@link Optional#empty()} when the email is not
 * registered — the controller responds 204 in BOTH cases so an
 * attacker probing the endpoint can't enumerate which emails exist.
 *
 * <p>Traces to: FR-04.
 */
@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public class RequestPasswordResetUseCase {

    public static final Duration TOKEN_TTL = Duration.ofMinutes(30);
    public static final int TOKEN_BYTES = 24; // 32 url-safe chars

    private static final Logger log = LoggerFactory.getLogger(RequestPasswordResetUseCase.class);
    private static final SecureRandom RNG = new SecureRandom();

    private final UserRepository users;
    private final Clock clock;

    public RequestPasswordResetUseCase(UserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    @Transactional
    @Auditable(action = "PASSWORD_RESET_REQUESTED", entityType = "user")
    public Optional<String> handle(String rawEmail) {
        Email email;
        try {
            email = Email.of(rawEmail);
        } catch (Exception e) {
            return Optional.empty();
        }

        Optional<User> maybeUser = users.findByEmail(email);
        if (maybeUser.isEmpty()) {
            // Don't leak which emails exist.
            return Optional.empty();
        }
        User user = maybeUser.get();
        String token = mintToken();
        Instant expires = Instant.now(clock).plus(TOKEN_TTL);
        users.setResetToken(user.id(), token, expires);

        // Demo mode: log the token at INFO. Production wiring would
        // hand this off to an email service.
        log.info(
                "Password reset requested for user {} — token={} expiresAt={}",
                user.id().value(),
                token,
                expires);

        return Optional.of(token);
    }

    private static String mintToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RNG.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
