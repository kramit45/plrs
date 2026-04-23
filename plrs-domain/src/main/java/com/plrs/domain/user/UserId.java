package com.plrs.domain.user;

import com.plrs.domain.common.DomainValidationException;
import java.util.Objects;
import java.util.UUID;

/**
 * Typed identifier for a user. Wrapping {@link UUID} in a dedicated type
 * prevents accidental cross-wiring between ids of different entities
 * (passing a {@code ContentId} where a {@code UserId} is expected is a
 * compile error rather than a runtime mystery) and gives a single place to
 * enforce constructor validation.
 *
 * <p>This is intentionally a {@code final class} and not a {@code record} so
 * that:
 *
 * <ul>
 *   <li>the constructor can stay {@code private} and all instantiation
 *       flows through the named factories ({@link #newId()},
 *       {@link #of(UUID)}, {@link #of(String)}), making validation
 *       impossible to bypass, and
 *   <li>a future Hibernate {@code UserType} can be attached without
 *       fighting the record's generated accessors.
 * </ul>
 *
 * <p>Null and invalid inputs throw {@link DomainValidationException} so the
 * web layer can translate them to HTTP 400 with a single handler.
 *
 * <p>Traces to: §3.a (typed IDs — UserId is one of 8 value objects).
 */
public final class UserId {

    private final UUID value;

    private UserId(UUID value) {
        this.value = value;
    }

    /** Generates a fresh, random UserId backed by {@link UUID#randomUUID()}. */
    public static UserId newId() {
        return new UserId(UUID.randomUUID());
    }

    /**
     * Wraps an existing UUID as a UserId.
     *
     * @throws DomainValidationException when {@code value} is null
     */
    public static UserId of(UUID value) {
        if (value == null) {
            throw new DomainValidationException("UserId value must not be null");
        }
        return new UserId(value);
    }

    /**
     * Parses a UUID string into a UserId.
     *
     * @throws DomainValidationException when {@code value} is null or not a valid UUID
     */
    public static UserId of(String value) {
        if (value == null) {
            throw new DomainValidationException("UserId value must not be null");
        }
        try {
            return new UserId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new DomainValidationException("UserId value is not a valid UUID: " + value, e);
        }
    }

    public UUID value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserId other)) {
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
        return value.toString();
    }
}
