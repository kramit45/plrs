-- Traces to: FR-06 (account lockout: 5 failures within 15 min → 15-min lock).
--
-- Three columns on users + a partial index for the admin "currently locked"
-- query path. The lockout decision is made application-side in
-- SpringDataUserRepository.recordLoginFailure (single SQL with CASE so the
-- read-decide-write race is contained inside one statement).

ALTER TABLE plrs_ops.users
  ADD COLUMN failed_login_count INT          NOT NULL DEFAULT 0,
  ADD COLUMN last_fail_at       TIMESTAMPTZ,
  ADD COLUMN locked_until       TIMESTAMPTZ,
  ADD CONSTRAINT users_failed_count_nn CHECK (failed_login_count >= 0);

CREATE INDEX idx_users_locked
  ON plrs_ops.users (locked_until)
  WHERE locked_until IS NOT NULL;

COMMENT ON COLUMN plrs_ops.users.failed_login_count IS
  'Consecutive failed logins within the last 15 min; reset to 0 on success or when last_fail_at >= 15 min ago.';
COMMENT ON COLUMN plrs_ops.users.locked_until IS
  'Lock expiry timestamp. NULL means not locked. ADMIN unlock clears this column.';
