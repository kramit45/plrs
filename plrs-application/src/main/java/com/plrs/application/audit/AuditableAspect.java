package com.plrs.application.audit;

import com.plrs.domain.user.UserId;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * AOP advice that appends one {@link AuditEvent} after every successful
 * invocation of an {@link Auditable @Auditable}-marked method (NFR-29).
 *
 * <p>Order of operations:
 *
 * <ol>
 *   <li>Run the audited method first. A failed invocation propagates
 *       its exception untouched and is <em>not</em> audited — partial
 *       state was rolled back, and the exception is the more useful
 *       signal.
 *   <li>Resolve the actor from {@link SecurityContextHolder}. Anonymous
 *       and pre-login flows yield {@link Optional#empty()}.
 *   <li>{@link AuditRepository#append} the row inside the audited
 *       method's transaction (the @Around runs inside the same
 *       proxy chain) so the audit row commits atomically with the
 *       business state-change.
 * </ol>
 *
 * <p>An {@code append} failure is logged as WARN and swallowed: the
 * audited business action has already succeeded; failing it now would
 * roll back work the user can already observe (e.g. a successful
 * registration). NFR-29's append-or-fail invariant is enforced at the
 * DB boundary by TRG-4 (V13) for the strong cases that matter.
 */
@Aspect
@Component
@ConditionalOnProperty(name = "spring.datasource.url")
public class AuditableAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditableAspect.class);

    private final AuditRepository auditRepository;
    private final Clock clock;

    public AuditableAspect(AuditRepository auditRepository, Clock clock) {
        this.auditRepository = auditRepository;
        this.clock = clock;
    }

    @Around("@annotation(auditable)")
    public Object around(ProceedingJoinPoint pjp, Auditable auditable) throws Throwable {
        Object result = pjp.proceed();
        try {
            Optional<UserId> actor = resolveActor();
            String entityType = auditable.entityType();
            AuditEvent event =
                    new AuditEvent(
                            actor,
                            auditable.action(),
                            entityType.isEmpty() ? Optional.empty() : Optional.of(entityType),
                            Optional.empty(),
                            Optional.empty(),
                            Instant.now(clock));
            auditRepository.append(event);
        } catch (Exception e) {
            log.warn("Audit append failed for action {}", auditable.action(), e);
        }
        return result;
    }

    private static Optional<UserId> resolveActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return Optional.empty();
        }
        String name = auth.getName();
        if (name == null || name.isEmpty() || "anonymousUser".equals(name)) {
            return Optional.empty();
        }
        try {
            return Optional.of(UserId.of(UUID.fromString(name)));
        } catch (IllegalArgumentException e) {
            // Principal name isn't a UUID (e.g. a service account or a
            // test fixture) — record it as anonymous rather than failing
            // the audit append.
            return Optional.empty();
        }
    }
}
