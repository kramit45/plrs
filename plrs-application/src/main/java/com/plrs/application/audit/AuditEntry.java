package com.plrs.application.audit;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-side projection of one row in {@code plrs_ops.audit_log} for
 * the FR-42 admin viewer. {@code detailJson} is exposed as the raw
 * JSON string so the UI can pretty-print or truncate at render time
 * without the read path needing to know the per-action shape.
 */
public record AuditEntry(
        long auditId,
        Instant occurredAt,
        Optional<UUID> actorUserId,
        String action,
        Optional<String> entityType,
        Optional<String> entityId,
        Optional<String> detailJson) {}
