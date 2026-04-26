-- Traces to: NFR-35 (bias / diversity guardrails alongside accuracy).
-- Adds the two recommender-bias metrics to fact_eval_run so the admin
-- dashboard can show them next to precision@k / nDCG@k / coverage.
--
-- Spec said V24 ALTER; V24 was already taken by integrity_checks
-- (Iter 4). This is the next free version slot.
--
-- Both columns are nullable: existing rows pre-date the metrics and a
-- SKIPPED ML run leaves them blank.

ALTER TABLE plrs_dw.fact_eval_run
  ADD COLUMN IF NOT EXISTS diversity NUMERIC(5,4),
  ADD COLUMN IF NOT EXISTS novelty   NUMERIC(7,4);

COMMENT ON COLUMN plrs_dw.fact_eval_run.diversity IS
  'Mean intra-list pairwise (1 - cosine TF-IDF) over the top-k. Range [0,1].';
COMMENT ON COLUMN plrs_dw.fact_eval_run.novelty IS
  'Mean -log2(popularity / n_users) over recommended items. Higher = rarer items.';
