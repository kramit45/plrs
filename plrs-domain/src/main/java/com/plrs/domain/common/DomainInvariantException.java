package com.plrs.domain.common;

/**
 * Thrown when an aggregate-level invariant is violated — a rule that
 * spans more than one field and belongs to an entity or aggregate rather
 * than to a single value object. Example: "an ADMIN user must have a
 * confirmed email" is an invariant of the User aggregate (step 23+),
 * whereas "email must match the RFC regex" is a value-object validation
 * and throws the parent type.
 *
 * <p>Separating the two lets catch sites distinguish "the caller gave us
 * bad input" (value-object violation) from "the caller asked us to reach
 * a state the aggregate forbids" (invariant violation) without introducing
 * a second exception root.
 *
 * <p>Traces to: §3.b (invariants).
 */
public class DomainInvariantException extends DomainValidationException {

    public DomainInvariantException(String message) {
        super(message);
    }

    public DomainInvariantException(String message, Throwable cause) {
        super(message, cause);
    }
}
