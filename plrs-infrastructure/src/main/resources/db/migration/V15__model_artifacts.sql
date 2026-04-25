-- Traces to: §3.c.1.5 model_artifacts. Durable backup for ML artifacts
-- (item-item similarity matrix, TF-IDF, popularity slabs) so a Redis
-- cold start can rehydrate without re-running the @Scheduled jobs.
--
-- Filename note: V15 (not V14 as the step prompt suggested) because V14
-- was used by the recommendations migration in step 104.
--
-- artifact_type identifies the producer; artifact_key disambiguates
-- multiple slabs of the same type (e.g. one row per content_id for
-- SIM_SLAB, a single row keyed "matrix" for ITEM_ITEM_MATRIX or
-- TFIDF). version is a free-form opaque tag (typically the producer's
-- compute timestamp) the consumer compares against to invalidate.

CREATE TABLE plrs_ops.model_artifacts (
  artifact_type VARCHAR(30) NOT NULL,
  artifact_key  VARCHAR(60) NOT NULL,
  payload       BYTEA       NOT NULL,
  version       VARCHAR(30) NOT NULL,
  computed_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  size_bytes    INT         NOT NULL,
  PRIMARY KEY (artifact_type, artifact_key),
  CONSTRAINT artifact_type_enum CHECK (artifact_type IN
    ('TFIDF',
     'SIM_SLAB',
     'POPULARITY_TOPIC',
     'POPULARITY_GLOBAL',
     'ITEM_ITEM_MATRIX')),
  CONSTRAINT artifact_size_nn CHECK (size_bytes > 0)
);

CREATE INDEX idx_artifacts_version ON plrs_ops.model_artifacts(version);

COMMENT ON TABLE plrs_ops.model_artifacts IS
  'Durable backup of ML artifacts; Redis is the hot read path';
COMMENT ON COLUMN plrs_ops.model_artifacts.payload IS
  'Opaque BYTEA — typically Jackson-serialised JSON or compact binary';
COMMENT ON COLUMN plrs_ops.model_artifacts.version IS
  'Free-form producer-stamped version tag (typically compute timestamp)';
