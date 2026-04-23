package com.plrs.infrastructure.security;

import com.plrs.application.security.PasswordEncoder;
import com.plrs.domain.user.BCryptHash;
import com.plrs.domain.user.PasswordPolicy;
import org.springframework.stereotype.Component;

/**
 * BCrypt-backed implementation of the application
 * {@link PasswordEncoder} port, delegating to Spring Security Crypto's
 * {@code BCryptPasswordEncoder}. Cost is pinned to 12 per §7; a config
 * property is deliberately <em>not</em> exposed — the cost is a security
 * decision, not a tuning knob, and changing it is a migration event
 * (accompanied by a hash-rotation plan) rather than an environment switch.
 *
 * <p>The encode path chains three checks so violations surface at the
 * earliest possible layer:
 *
 * <ol>
 *   <li>{@link PasswordPolicy#validate(String)} rejects weak or malformed
 *       inputs before any hashing work happens,
 *   <li>the BCrypt delegate produces a 60-character {@code $2a$12$…} hash,
 *   <li>{@link BCryptHash#of(String)} re-validates that hash — redundant
 *       on the happy path, but a defence-in-depth guard against future
 *       refactors that swap the delegate for something weaker.
 * </ol>
 *
 * <p>{@link #matches(String, BCryptHash)} returns {@code false} for null
 * inputs on either side so the authentication service does not need to
 * thread null checks for "user absent" or "no password provided" cases.
 *
 * <p>Traces to: §7 (BCrypt cost 12).
 */
@Component
public final class BCryptPasswordEncoderAdapter implements PasswordEncoder {

    static final int COST = 12;

    private final org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder delegate =
            new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(COST);

    @Override
    public BCryptHash encode(String rawPassword) {
        PasswordPolicy.validate(rawPassword);
        return BCryptHash.of(delegate.encode(rawPassword));
    }

    @Override
    public boolean matches(String rawPassword, BCryptHash hash) {
        if (rawPassword == null || hash == null) {
            return false;
        }
        return delegate.matches(rawPassword, hash.value());
    }
}
