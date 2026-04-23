package com.plrs.domain.user;

import com.plrs.domain.common.DomainValidationException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Value object wrapping an already-computed BCrypt password hash.
 *
 * <p>This type is deliberately <em>not</em> a hasher: it validates the
 * <em>shape</em> of a stored hash produced by the application-layer
 * password encoder (step 30), and shields call sites from accidentally
 * storing something that looks like a hash but isn't (a plain password,
 * a legacy SHA-1 digest, a half-escaped Base64 blob, etc.). Keeping the
 * rule here means persistence can trust inputs without re-validating.
 *
 * <p>Validation rules:
 *
 * <ul>
 *   <li>non-null, non-blank,
 *   <li>matches the BCrypt canonical form
 *       {@code ^\$2[aby]\$\d{2}\$[./A-Za-z0-9]{53}$} (prefix, 2-digit cost,
 *       22-char salt + 31-char hash in the BCrypt alphabet), and
 *   <li>the parsed cost is at least 12, per §7.
 * </ul>
 *
 * <p>{@link #toString()} is deliberately <b>masked</b> — it returns the
 * prefix + cost + {@code ***} rather than the full hash — so a hash
 * instance accidentally printed into a log line does not leak the stored
 * credential material. {@link #value()} returns the full hash for the
 * narrow persistence path that needs it.
 *
 * <p>Violations throw {@link DomainValidationException} so the web layer can
 * translate them to HTTP 400 with a single handler.
 *
 * <p>Traces to: §3.a (value objects), §7 (BCrypt cost 12).
 */
public final class BCryptHash {

    static final int MIN_COST = 12;

    private static final Pattern PATTERN =
            Pattern.compile("^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$");

    private final String value;

    private BCryptHash(String value) {
        this.value = value;
    }

    /**
     * Wraps a stored BCrypt hash after validating its shape and cost.
     *
     * @throws DomainValidationException when {@code raw} is null, blank,
     *     structurally invalid, or uses a cost below {@link #MIN_COST}
     */
    public static BCryptHash of(String raw) {
        if (raw == null) {
            throw new DomainValidationException("BCryptHash must not be null");
        }
        if (raw.isBlank()) {
            throw new DomainValidationException("BCryptHash must not be blank");
        }
        if (!PATTERN.matcher(raw).matches()) {
            throw new DomainValidationException("BCryptHash is not a valid bcrypt hash");
        }
        int cost = Integer.parseInt(raw.substring(4, 6));
        if (cost < MIN_COST) {
            throw new DomainValidationException(
                    "BCryptHash cost must be at least " + MIN_COST + " (was " + cost + ")");
        }
        return new BCryptHash(raw);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BCryptHash other)) {
            return false;
        }
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    /**
     * Returns a masked form — the first 7 characters ({@code $2b$12$}) plus
     * {@code ***}. Never returns the full hash so that a value accidentally
     * interpolated into a log line or exception trace does not leak the
     * stored credential material.
     */
    @Override
    public String toString() {
        return value.substring(0, 7) + "***";
    }
}
