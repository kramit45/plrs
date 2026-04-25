-- Traces to: §3.c.1.5 audit_log, §3.b.5.4 (TRG-4 append-only), NFR-29
--
-- Filename note: V13 (not V12 as the step prompt suggested) because V12
-- was used by the user_skills migration in step 88.
--
-- The audit_log captures every state-changing application action via
-- the @Auditable AOP aspect (step 95). Writes happen inside the same
-- transaction as the audited method so partially-failed actions never
-- produce orphan audit rows.
--
-- TRG-4 enforces append-only at the database boundary: no UPDATEs ever
-- (the log is the canonical record), and DELETEs only when the session
-- role is plrs_retention_role (the privileged retention sweeper that
-- prunes rows past the documented retention window).

CREATE TABLE plrs_ops.audit_log (
  audit_id      BIGSERIAL    PRIMARY KEY,
  occurred_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  actor_user_id UUID         REFERENCES plrs_ops.users(id) ON DELETE SET NULL,
  action        VARCHAR(60)  NOT NULL,
  entity_type   VARCHAR(40),
  entity_id     VARCHAR(60),
  source_ip     INET,
  user_agent    VARCHAR(300),
  detail_json   JSONB,
  CONSTRAINT audit_action_nn CHECK (length(trim(action)) > 0)
);

CREATE INDEX idx_audit_occurred ON plrs_ops.audit_log(occurred_at DESC);
CREATE INDEX idx_audit_actor    ON plrs_ops.audit_log(actor_user_id, occurred_at DESC)
  WHERE actor_user_id IS NOT NULL;
CREATE INDEX idx_audit_action   ON plrs_ops.audit_log(action, occurred_at DESC);

-- TRG-4: append-only enforcement. UPDATEs are forbidden unconditionally
-- (the audit_log mutates only via INSERT). DELETEs are forbidden except
-- for the retention role; this lets the operational sweep prune rows
-- without weakening the guarantee for application code.
CREATE OR REPLACE FUNCTION plrs_ops.fn_prevent_audit_change() RETURNS TRIGGER AS $$
BEGIN
  RAISE EXCEPTION 'audit_log is append-only' USING ERRCODE = '42501';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_no_update
  BEFORE UPDATE ON plrs_ops.audit_log
  FOR EACH ROW
  EXECUTE FUNCTION plrs_ops.fn_prevent_audit_change();

CREATE TRIGGER trg_audit_no_delete
  BEFORE DELETE ON plrs_ops.audit_log
  FOR EACH ROW
  WHEN (current_user <> 'plrs_retention_role')
  EXECUTE FUNCTION plrs_ops.fn_prevent_audit_change();

COMMENT ON TABLE plrs_ops.audit_log IS
  'Append-only audit trail for state-changing application actions (NFR-29)';
COMMENT ON COLUMN plrs_ops.audit_log.actor_user_id IS
  'Authenticated user that performed the action; NULL for anonymous flows';
COMMENT ON COLUMN plrs_ops.audit_log.action IS
  'Stable identifier of the action (e.g. USER_REGISTERED, QUIZ_ATTEMPTED)';
COMMENT ON COLUMN plrs_ops.audit_log.detail_json IS
  'Free-form JSON payload; producers should keep it small and PII-light';
