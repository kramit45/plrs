package com.plrs.application.audit;

/**
 * Application-layer port for the audit trail. The infrastructure
 * adapter writes to {@code plrs_ops.audit_log} (V13). Append-only by
 * contract — there is no read or delete surface here.
 */
public interface AuditRepository {

    /** Persists one audit row. */
    void append(AuditEvent event);
}
