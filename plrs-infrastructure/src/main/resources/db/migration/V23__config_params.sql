-- Traces to: FR-40 (admin runtime tunables).
--
-- Single key/value table for runtime-mutable parameters that the admin
-- can edit without a deploy. ConfigParamService caches reads
-- (@Cacheable) and evicts on update so latency stays low. value_type
-- is an enum so the service can parse the string back to the right
-- Java type without callers re-parsing.

CREATE TABLE plrs_ops.config_params (
  param_name   VARCHAR(80)  PRIMARY KEY,
  param_value  VARCHAR(200) NOT NULL,
  value_type   VARCHAR(15)  NOT NULL DEFAULT 'STRING',
  description  VARCHAR(300),
  updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  updated_by   UUID         REFERENCES plrs_ops.users(id) ON DELETE SET NULL,
  CONSTRAINT config_value_type_enum CHECK (value_type IN ('STRING','INT','DOUBLE','BOOLEAN'))
);

INSERT INTO plrs_ops.config_params (param_name, param_value, value_type, description) VALUES
  ('rec.lambda_blend',             '0.65', 'DOUBLE',  'CF/CB blend weight (HybridRanker)'),
  ('rec.lambda_mmr',               '0.30', 'DOUBLE',  'MMR diversity weight (MmrReranker)'),
  ('rec.cache_ttl_seconds',        '1800', 'INT',     'Top-N cache TTL'),
  ('rec.prereq_mastery_threshold', '0.60', 'DOUBLE',  'Prereq mastery threshold (RecommendationService)'),
  ('mastery.alpha_quiz',           '0.40', 'DOUBLE',  'EWMA alpha for quiz attempts'),
  ('path.skip_threshold',          '0.75', 'DOUBLE',  'Path planner skip-mastered threshold')
ON CONFLICT (param_name) DO NOTHING;

COMMENT ON TABLE plrs_ops.config_params IS
  'Runtime-mutable tunables (FR-40). Reads via ConfigParamService cache; admin-edits invalidate cache.';
