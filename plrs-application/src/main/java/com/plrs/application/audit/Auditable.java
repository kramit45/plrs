package com.plrs.application.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an application method as state-changing and audit-worthy
 * (NFR-29). The {@link AuditableAspect @Aspect} bound to this
 * annotation appends one {@link AuditEvent} per successful invocation,
 * inside the method's own transaction so the audit row and the
 * business write commit atomically.
 *
 * <p>Failed invocations are not audited — a stack-traced exception is
 * the more useful signal, and the partial state was rolled back.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {

    /** Stable identifier of the action, e.g. {@code "USER_REGISTERED"}. */
    String action();

    /** Optional entity type (e.g. {@code "content"}); {@code ""} for none. */
    String entityType() default "";
}
