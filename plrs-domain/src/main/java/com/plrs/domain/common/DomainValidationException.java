package com.plrs.domain.common;

/**
 * Thrown when a domain value object or policy rejects an input because it
 * violates the type's contract — null where a value was required, a blank
 * string, an out-of-range length, a pattern mismatch, and so on. Using a
 * dedicated runtime type (rather than {@link IllegalArgumentException})
 * lets the web layer translate domain-rule breaches into HTTP 400 with a
 * single {@code @ExceptionHandler} without swallowing unrelated JDK
 * argument checks.
 *
 * <p>Subclass {@link DomainInvariantException} is reserved for aggregate-level
 * invariants (see step 23); both sit under this single root so callers that
 * only care about "the domain said no" can catch one type.
 *
 * <p>Unchecked by design — validation breaches are a programming or request
 * error, not a recoverable condition — so callers do not have to thread
 * checked throws through ports and services.
 *
 * <p>Traces to: §5.b (error handling standards).
 */
public class DomainValidationException extends RuntimeException {

    public DomainValidationException(String message) {
        super(message);
    }

    public DomainValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
