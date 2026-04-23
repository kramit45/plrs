package com.plrs.application.security;

import com.plrs.domain.user.BCryptHash;

/**
 * Application-owned port for producing and verifying password hashes. The
 * port is declared here rather than in the domain because hashing is an
 * orchestration concern — a service-level step between accepting a raw
 * password and handing it to persistence — not an intrinsic property of
 * the {@code User} aggregate. The domain remains pure; the infrastructure
 * module supplies a BCrypt-backed adapter (see
 * {@code com.plrs.infrastructure.security.BCryptPasswordEncoderAdapter}).
 *
 * <p>The port is deliberately narrow: one method to encode, one to verify.
 * Returning {@link BCryptHash} from {@link #encode(String)} — rather than a
 * raw {@code String} — means callers cannot accidentally persist an
 * unvalidated hash; the value object's shape+cost check is the last line
 * of defence before a write.
 *
 * <p>Traces to: §7 (BCrypt cost 12), §3.a (application-owned ports).
 */
public interface PasswordEncoder {

    /**
     * Validates the raw password against the project's password policy, then
     * hashes it with BCrypt and wraps the result as a {@link BCryptHash}.
     *
     * @throws com.plrs.domain.common.DomainValidationException when the raw
     *     password violates {@link com.plrs.domain.user.PasswordPolicy} or
     *     the produced hash fails {@link BCryptHash}'s shape/cost check
     */
    BCryptHash encode(String rawPassword);

    /**
     * Verifies a raw password against a stored hash. Null inputs on either
     * side return {@code false} — callers can pass potentially-missing
     * credentials without threading null checks through the service layer.
     */
    boolean matches(String rawPassword, BCryptHash hash);
}
