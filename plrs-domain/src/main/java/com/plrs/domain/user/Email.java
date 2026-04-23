package com.plrs.domain.user;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validated email-address value object.
 *
 * <p>Inputs are normalised before validation: leading and trailing whitespace
 * is trimmed and the string is lowercased. The normalised form is what is
 * stored and what {@link #value()}, {@link #toString()} and the equality
 * contract see — so {@code Email.of("  Kumar@Example.COM  ")} equals
 * {@code Email.of("kumar@example.com")}.
 *
 * <p>Validation rules:
 *
 * <ul>
 *   <li>must not be null or blank (after trimming),
 *   <li>must be at most 254 characters (the RFC 5321 path length cap), and
 *   <li>must match a pragmatic regex, {@code ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$}.
 * </ul>
 *
 * <p>The regex is <b>pragmatic, not fully RFC 5322</b>. It catches the cases
 * users actually mistype (missing {@code @}, missing TLD, missing local part)
 * and is the same shape as the Hibernate Validator {@code @Email} default.
 * It does <em>not</em> reject structurally legal-but-unusual forms like a
 * leading dot in the local part ({@code .leading.dot@example.com}) or
 * consecutive dots. If the project ever needs strict RFC compliance, swap
 * this single regex for a full grammar parser — callers do not see the regex.
 *
 * <p>Null and invalid inputs throw {@link IllegalArgumentException} today;
 * step 22 will introduce a {@code DomainValidationException} and sweep this,
 * {@code UserId} and {@code BCryptHash} over together.
 *
 * <p>Traces to: §3.a (value objects), §3.b (invariants — email format).
 */
public final class Email {

    private static final int MAX_LENGTH = 254;

    private static final Pattern PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private final String value;

    private Email(String value) {
        this.value = value;
    }

    /**
     * Parses and normalises a raw email string.
     *
     * @throws IllegalArgumentException with a message naming the violated rule
     *     when {@code raw} is null, blank, too long, or malformed
     */
    public static Email of(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Email must not be null");
        }
        String normalised = raw.trim().toLowerCase();
        if (normalised.isEmpty()) {
            throw new IllegalArgumentException("Email must not be blank");
        }
        if (normalised.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "Email must be at most " + MAX_LENGTH + " characters (RFC 5321)");
        }
        if (!PATTERN.matcher(normalised).matches()) {
            throw new IllegalArgumentException("Email format is invalid: " + raw);
        }
        return new Email(normalised);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Email other)) {
            return false;
        }
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
