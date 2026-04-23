-- Traces to: §3.c (two-schema PostgreSQL layout: plrs_ops + plrs_dw).
-- Flyway manages these schemas as configured in spring.flyway.schemas; the
-- IF NOT EXISTS guards keep the migration idempotent so a re-baseline against
-- an existing database is a no-op.

CREATE SCHEMA IF NOT EXISTS plrs_ops;
CREATE SCHEMA IF NOT EXISTS plrs_dw;

COMMENT ON SCHEMA plrs_ops IS 'Operational schema for PLRS application data';
COMMENT ON SCHEMA plrs_dw IS 'Data warehouse schema for analytics and reporting';
