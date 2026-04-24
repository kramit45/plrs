package com.plrs.domain.topic;

import com.plrs.domain.common.DomainValidationException;
import java.util.Objects;

/**
 * Typed identifier for a topic. Wrapping {@link Long} in a dedicated type
 * prevents accidental cross-wiring between ids of different entities
 * (passing a {@code ContentId} where a {@code TopicId} is expected is a
 * compile error rather than a runtime mystery) and gives a single place to
 * enforce constructor validation.
 *
 * <p>Unlike {@link com.plrs.domain.user.UserId}, which wraps a {@code UUID},
 * TopicId is backed by a {@code Long}: PLRS uses {@code BIGSERIAL} for all
 * surrogate keys on server-generated entities, so the raw id travels through
 * the system as a 64-bit integer. The web-facing slug (stable, human-visible)
 * is a separate value object introduced later in the iteration.
 *
 * <p>This is intentionally a {@code final class} and not a {@code record} so
 * that:
 *
 * <ul>
 *   <li>the constructor can stay {@code private} and all instantiation flows
 *       through the named factories ({@link #of(Long)}, {@link #of(long)}),
 *       making validation impossible to bypass, and
 *   <li>a future Hibernate {@code UserType} can be attached without fighting
 *       the record's generated accessors.
 * </ul>
 *
 * <p>Null, zero, and negative inputs throw {@link DomainValidationException} so
 * the web layer can translate them to HTTP 400 with a single handler.
 * Surrogate keys are strictly positive by BIGSERIAL semantics; a zero or
 * negative id indicates a bug or a hand-crafted request.
 *
 * <p>Traces to: §3.a (typed IDs), §3.c.4 (BIGSERIAL surrogate).
 */
public final class TopicId {

    private final Long value;

    private TopicId(Long value) {
        this.value = value;
    }

    /**
     * Wraps an existing Long as a TopicId.
     *
     * @throws DomainValidationException when {@code value} is null or not positive
     */
    public static TopicId of(Long value) {
        if (value == null) {
            throw new DomainValidationException("TopicId value must not be null");
        }
        if (value <= 0L) {
            throw new DomainValidationException(
                    "TopicId value must be positive (BIGSERIAL surrogate), got " + value);
        }
        return new TopicId(value);
    }

    /**
     * Wraps a primitive {@code long} as a TopicId. Convenience overload for
     * callers that already hold the id as a primitive (e.g., JDBC
     * {@code ResultSet#getLong}) and would otherwise autobox for no reason.
     *
     * @throws DomainValidationException when {@code value} is not positive
     */
    public static TopicId of(long value) {
        if (value <= 0L) {
            throw new DomainValidationException(
                    "TopicId value must be positive (BIGSERIAL surrogate), got " + value);
        }
        return new TopicId(value);
    }

    public Long value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TopicId other)) {
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
        return "TopicId(" + value + ")";
    }
}
