package com.plrs.application.audit;

import com.plrs.domain.user.UserId;
import java.time.Instant;
import java.util.Optional;

/**
 * One row of {@code plrs_ops.audit_log} (V13). Lives in
 * {@code plrs-application} (not {@code plrs-domain}) — the audit trail
 * is a persistence-coupling concern, not a domain invariant.
 *
 * <p>{@code actorUserId} is empty for anonymous flows (e.g. registration,
 * pre-login). {@code detailJson} is opaque to the publisher; callers
 * keep it small and PII-light to match the column comment on V13.
 *
 * <p>Traces to: §3.b.5.4 (TRG-4 append-only), §3.c.1.5 (audit_log
 * schema), NFR-29 (audit-every-mutation).
 */
public record AuditEvent(
        Optional<UserId> actorUserId,
        String action,
        Optional<String> entityType,
        Optional<String> entityId,
        Optional<String> detailJson,
        Instant occurredAt) {}
