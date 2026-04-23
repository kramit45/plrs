-- Traces to: §3.c (user_roles join table), §7 (additive role model,
-- bounded enum STUDENT/INSTRUCTOR/ADMIN).
-- A CHECK constraint rather than a roles lookup table because the set is
-- small, closed, and owned by the domain enum Role (step 20) — the extra
-- join and FK would cost more than the centralisation is worth. The CHECK
-- stays in lockstep with the enum: whenever Role gains a value, a new
-- Flyway migration updates this constraint.
-- ON DELETE CASCADE keeps the join table consistent with user deletion;
-- rows in user_roles have no independent lifecycle.

CREATE TABLE plrs_ops.user_roles (
  user_id     UUID         NOT NULL REFERENCES plrs_ops.users(id) ON DELETE CASCADE,
  role        VARCHAR(16)  NOT NULL,
  assigned_at TIMESTAMPTZ  NOT NULL,
  CONSTRAINT user_roles_pk    PRIMARY KEY (user_id, role),
  CONSTRAINT user_roles_value CHECK (role IN ('STUDENT', 'INSTRUCTOR', 'ADMIN'))
);

CREATE INDEX idx_user_roles_role ON plrs_ops.user_roles(role);

COMMENT ON TABLE plrs_ops.user_roles IS 'Additive role assignments per user';
