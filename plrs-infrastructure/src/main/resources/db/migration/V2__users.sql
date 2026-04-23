-- Traces to: §3.c (users table), §3.b (email unique invariant).
-- Owning aggregate: com.plrs.domain.user.User. Column widths mirror the
-- domain validation rules so the database refuses values the value objects
-- would also refuse: email 254 (RFC 5321), password_hash 60 (BCrypt fixed
-- width), created_by 64. The CHECK constraint mirrors the AuditFields
-- invariant so writes that bypass the aggregate still cannot corrupt the
-- audit ordering.

CREATE TABLE plrs_ops.users (
  id             UUID         PRIMARY KEY,
  email          VARCHAR(254) NOT NULL,
  password_hash  VARCHAR(60)  NOT NULL,
  created_at     TIMESTAMPTZ  NOT NULL,
  updated_at     TIMESTAMPTZ  NOT NULL,
  created_by     VARCHAR(64)  NOT NULL,
  CONSTRAINT users_email_key UNIQUE (email),
  CONSTRAINT users_updated_after_created CHECK (updated_at >= created_at)
);

CREATE INDEX idx_users_created_at ON plrs_ops.users(created_at);

COMMENT ON TABLE plrs_ops.users IS 'Registered users; email unique and normalised lowercase';
COMMENT ON COLUMN plrs_ops.users.password_hash IS 'BCrypt cost>=12, $2a/$2b/$2y prefix';
