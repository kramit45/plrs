-- Traces to: FR-36, Iter 4 deviation note (recommender serve path
-- writes directly to plrs_dw.fact_recommendation so KPI MVs have data
-- to read without waiting for the Kafka → ETL hop).
--
-- The role split (plrs_app reads from dw, plrs_etl writes) is the
-- production intent. Iter 4 accepts the coupling for the demo
-- timeline; Iter 5 will replace this with a Kafka topic
-- plrs.recommendations consumed by plrs-etl-worker.
--
-- Idempotent: GRANT runs cleanly on every Flyway migrate, including
-- against the Testcontainers superuser where the grant is a no-op.

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'plrs_app_role') THEN
    GRANT INSERT ON plrs_dw.fact_recommendation TO plrs_app_role;
    GRANT INSERT, UPDATE ON plrs_dw.dim_user, plrs_dw.dim_content, plrs_dw.dim_topic
      TO plrs_app_role;
    GRANT USAGE ON ALL SEQUENCES IN SCHEMA plrs_dw TO plrs_app_role;
  END IF;
END $$;

COMMENT ON COLUMN plrs_dw.fact_recommendation.was_clicked IS
  'Stamped TRUE by SpringDataRecommendationRepository.recordClick (Iter 4 direct write); a future Kafka path will keep the same column.';
