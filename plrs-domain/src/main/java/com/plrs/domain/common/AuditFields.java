package com.plrs.domain.common;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable audit trio attached to every persisted aggregate: when it was
 * created, when it was last touched, and who created it. Kept as a pure
 * domain value object so aggregates can reason about their own history
 * without pulling in any persistence annotations — the JPA {@code @Embeddable}
 * counterpart lives in the infrastructure module (step 28) and copies into
 * and out of this type at the port boundary.
 *
 * <p>Invariants enforced by the canonical constructor:
 *
 * <ul>
 *   <li>{@code createdAt}, {@code updatedAt}, {@code createdBy} are all non-null,
 *   <li>{@code createdBy} is not blank, and
 *   <li>{@code updatedAt} is not before {@code createdAt}.
 * </ul>
 *
 * <p>Mutation is expressed as derivation: {@link #touchedAt(Instant)} and
 * {@link #touched(Clock)} return a new instance with a refreshed
 * {@code updatedAt}, leaving the receiver untouched. The {@link Clock}
 * overloads exist so tests and other callers can pin the current instant
 * without relying on {@link Instant#now()}.
 *
 * <p>Violations throw {@link DomainValidationException} so the web layer can
 * translate them to HTTP 400 with a single handler.
 *
 * <p>Traces to: §3.a (value objects), §3.b (invariants — audit trail).
 */
public final class AuditFields {

    private final Instant createdAt;
    private final Instant updatedAt;
    private final String createdBy;

    private AuditFields(Instant createdAt, Instant updatedAt, String createdBy) {
        if (createdBy == null) {
            throw new DomainValidationException("AuditFields createdBy must not be null");
        }
        if (createdBy.isBlank()) {
            throw new DomainValidationException("AuditFields createdBy must not be blank");
        }
        if (createdAt == null) {
            throw new DomainValidationException("AuditFields createdAt must not be null");
        }
        if (updatedAt == null) {
            throw new DomainValidationException("AuditFields updatedAt must not be null");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new DomainValidationException(
                    "AuditFields updatedAt (" + updatedAt + ") must not be before createdAt ("
                            + createdAt + ")");
        }
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.createdBy = createdBy;
    }

    /** Creates a fresh audit trio with both timestamps pinned to {@code now}. */
    public static AuditFields initial(String createdBy, Instant now) {
        return new AuditFields(now, now, createdBy);
    }

    /** Clock-driven overload of {@link #initial(String, Instant)} for deterministic tests. */
    public static AuditFields initial(String createdBy, Clock clock) {
        Objects.requireNonNull(clock, "clock");
        return initial(createdBy, Instant.now(clock));
    }

    /**
     * Returns a copy with {@code updatedAt} advanced to {@code now} while
     * preserving {@code createdAt} and {@code createdBy}.
     */
    public AuditFields touchedAt(Instant now) {
        return new AuditFields(createdAt, now, createdBy);
    }

    /** Clock-driven overload of {@link #touchedAt(Instant)}. */
    public AuditFields touched(Clock clock) {
        Objects.requireNonNull(clock, "clock");
        return touchedAt(Instant.now(clock));
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public String createdBy() {
        return createdBy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AuditFields other)) {
            return false;
        }
        return createdAt.equals(other.createdAt)
                && updatedAt.equals(other.updatedAt)
                && createdBy.equals(other.createdBy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(createdAt, updatedAt, createdBy);
    }

    @Override
    public String toString() {
        return "AuditFields{createdAt=" + createdAt
                + ", updatedAt=" + updatedAt
                + ", createdBy='" + createdBy + "'}";
    }
}
