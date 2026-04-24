package com.plrs.domain.content;

import com.plrs.domain.common.DomainValidationException;
import java.util.Objects;

/**
 * Typed identifier for a content item (a lesson/article/video tied to a
 * topic). Wrapping {@link Long} in a dedicated type prevents accidental
 * cross-wiring between ids of different entities (passing a {@code TopicId}
 * where a {@code ContentId} is expected is a compile error rather than a
 * runtime mystery) and gives a single place to enforce constructor
 * validation.
 *
 * <p>Backed by a {@code Long} because PLRS uses {@code BIGSERIAL} for
 * server-generated surrogate keys, mirroring
 * {@link com.plrs.domain.topic.TopicId}. {@link com.plrs.domain.user.UserId}
 * stays UUID-backed (Iter 1 design); do not assume all typed ids share the
 * same underlying primitive.
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
public final class ContentId {

    private final Long value;

    private ContentId(Long value) {
        this.value = value;
    }

    /**
     * Wraps an existing Long as a ContentId.
     *
     * @throws DomainValidationException when {@code value} is null or not positive
     */
    public static ContentId of(Long value) {
        if (value == null) {
            throw new DomainValidationException("ContentId value must not be null");
        }
        if (value <= 0L) {
            throw new DomainValidationException(
                    "ContentId value must be positive (BIGSERIAL surrogate), got " + value);
        }
        return new ContentId(value);
    }

    /**
     * Wraps a primitive {@code long} as a ContentId. Convenience overload for
     * callers that already hold the id as a primitive (e.g., JDBC
     * {@code ResultSet#getLong}) and would otherwise autobox for no reason.
     *
     * @throws DomainValidationException when {@code value} is not positive
     */
    public static ContentId of(long value) {
        if (value <= 0L) {
            throw new DomainValidationException(
                    "ContentId value must be positive (BIGSERIAL surrogate), got " + value);
        }
        return new ContentId(value);
    }

    public Long value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ContentId other)) {
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
        return "ContentId(" + value + ")";
    }
}
