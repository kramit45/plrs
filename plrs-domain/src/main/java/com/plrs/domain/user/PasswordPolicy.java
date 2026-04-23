package com.plrs.domain.user;

import com.plrs.domain.common.DomainValidationException;

/**
 * Stateless domain policy for raw passwords. Callers pass the plain string
 * just before hashing so violations surface before anything touches
 * persistence or the hash algorithm. The rules are intentionally modest —
 * §7 mandates the minimum length as the headline control; the letter+digit
 * requirement is the lightest complexity check that still rules out the
 * two common weak cases (all-digits PINs and all-letters dictionary words).
 *
 * <p>This policy does <em>not</em> hash passwords or deal with encoded
 * forms — {@code BCryptHash} (step 19) owns the hashed representation and
 * application-layer services (step 30) drive the encoder.
 *
 * <p>Violations throw {@link DomainValidationException} so the web layer can
 * translate them to HTTP 400 with a single handler.
 *
 * <p>Traces to: §7 (auth password policy).
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 10;

    private PasswordPolicy() {}

    /**
     * Validates a raw password against the project's length + complexity rule.
     *
     * @throws DomainValidationException when {@code raw} is null, blank,
     *     shorter than {@link #MIN_LENGTH}, missing a letter, or missing a digit
     */
    public static void validate(String raw) {
        if (raw == null) {
            throw new DomainValidationException("Password must not be null");
        }
        if (raw.isBlank()) {
            throw new DomainValidationException("Password must not be blank");
        }
        if (raw.length() < MIN_LENGTH) {
            throw new DomainValidationException(
                    "Password must be at least " + MIN_LENGTH + " characters");
        }

        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                hasLetter = true;
            } else if (c >= '0' && c <= '9') {
                hasDigit = true;
            }
            if (hasLetter && hasDigit) {
                return;
            }
        }

        if (!hasLetter) {
            throw new DomainValidationException("Password must contain at least one letter");
        }
        throw new DomainValidationException("Password must contain at least one digit");
    }
}
