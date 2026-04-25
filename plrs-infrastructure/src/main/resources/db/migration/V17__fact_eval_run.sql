-- Traces to: §3.c.2 fact_eval_run, FR-45.
-- Records each offline evaluation harness invocation; populated by
-- AdminEvalController -> RunEvalUseCase. One row per /api/admin/eval/run
-- call; ran_at is the timestamp the metrics were computed at.

CREATE TABLE plrs_dw.fact_eval_run (
  eval_run_sk    BIGSERIAL    PRIMARY KEY,
  ran_at         TIMESTAMPTZ  NOT NULL,
  variant_name   VARCHAR(30)  NOT NULL,
  k              SMALLINT     NOT NULL DEFAULT 10,
  precision_at_k NUMERIC(5,4),
  ndcg_at_k      NUMERIC(5,4),
  coverage       NUMERIC(5,4),
  n_users        INT
);

CREATE INDEX idx_eval_run_ran_at ON plrs_dw.fact_eval_run(ran_at DESC);

COMMENT ON TABLE plrs_dw.fact_eval_run IS
  'Persisted output of offline-eval runs (§3.c.5.8 algorithm A8).';
