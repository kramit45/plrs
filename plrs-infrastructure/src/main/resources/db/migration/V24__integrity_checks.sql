-- Traces to: §3.b.5.5, §3.b.8.3 (defence-in-depth integrity log).
--
-- Append-only log of each nightly IntegrityChecksJob run. status is a
-- bounded enum so the admin dashboard can colour-code rows without a
-- regex on free-form text. detail_json holds the failing-row payload
-- for FAIL/WARN entries; OK rows leave it NULL to keep the log slim.

CREATE TABLE plrs_ops.integrity_checks (
  check_id     BIGSERIAL    PRIMARY KEY,
  check_name   VARCHAR(80)  NOT NULL,
  run_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  status       VARCHAR(15)  NOT NULL,
  detail_json  JSONB,
  CONSTRAINT icheck_status_enum CHECK (status IN ('OK', 'WARN', 'FAIL'))
);

CREATE INDEX idx_icheck_recent
  ON plrs_ops.integrity_checks (check_name, run_at DESC);

COMMENT ON TABLE plrs_ops.integrity_checks IS
  'Append-only log of nightly integrity-checks runs. Each check produces one row per run.';
