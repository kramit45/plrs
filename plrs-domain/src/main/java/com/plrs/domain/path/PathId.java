package com.plrs.domain.path;

import com.plrs.domain.common.DomainValidationException;
import java.util.Objects;

/**
 * Typed identifier for a learner path. Wrapping {@link Long} mirrors
 * {@link com.plrs.domain.topic.TopicId} and
 * {@link com.plrs.domain.content.ContentId} — surrogate {@code BIGSERIAL}
 * key, strictly positive, never null.
 *
 * <p>Final class with a private constructor so all instantiation flows
 * through {@link #of(Long)} / {@link #of(long)} and validation cannot be
 * bypassed.
 *
 * <p>Traces to: §3.a (typed IDs), §3.c.1.4 (learner_paths surrogate).
 */
public final class PathId {

    private final Long value;

    private PathId(Long value) {
        this.value = value;
    }

    /**
     * Wraps an existing Long as a PathId.
     *
     * @throws DomainValidationException when {@code value} is null or not positive
     */
    public static PathId of(Long value) {
        if (value == null) {
            throw new DomainValidationException("PathId value must not be null");
        }
        if (value <= 0L) {
            throw new DomainValidationException(
                    "PathId value must be positive (BIGSERIAL surrogate), got " + value);
        }
        return new PathId(value);
    }

    /**
     * Wraps a primitive {@code long} as a PathId. Convenience overload for
     * callers that already hold the id as a primitive.
     *
     * @throws DomainValidationException when {@code value} is not positive
     */
    public static PathId of(long value) {
        if (value <= 0L) {
            throw new DomainValidationException(
                    "PathId value must be positive (BIGSERIAL surrogate), got " + value);
        }
        return new PathId(value);
    }

    public Long value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PathId other)) {
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
        return "PathId(" + value + ")";
    }
}
