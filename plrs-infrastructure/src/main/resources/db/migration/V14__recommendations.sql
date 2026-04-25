-- Traces to: §3.c.1.4 recommendations, FR-26 (rank), FR-27 (top-N serve),
-- FR-29 (reason text).
--
-- Filename note: V14 (not V13 as the step prompt suggested) because V13
-- was used by the audit_log migration in step 95.
--
-- Stores every recommendation served, with optional clicked_at and
-- completed_at timestamps populated later by the interaction pipeline
-- so the offline evaluation harness (Iter 3) can compute CTR @ K and
-- the served-history view in the dashboard can show "you saw this
-- recommended" trails.
--
-- Composite PK (user_id, content_id, created_at) lets the same content
-- be served to the same user across multiple slates: each serve gets a
-- distinct row keyed on the served-at timestamp. ON DELETE CASCADE on
-- both FKs keeps the table consistent if a user or content row is
-- deleted (the served history loses no semantic meaning once its
-- referent is gone).

CREATE TABLE plrs_ops.recommendations (
  user_id        UUID         NOT NULL REFERENCES plrs_ops.users(id) ON DELETE CASCADE,
  content_id     BIGINT       NOT NULL REFERENCES plrs_ops.content(content_id) ON DELETE CASCADE,
  created_at     TIMESTAMPTZ  NOT NULL,
  score          NUMERIC(6,4) NOT NULL,
  rank_position  SMALLINT     NOT NULL,
  reason_text    VARCHAR(200) NOT NULL,
  model_variant  VARCHAR(30)  NOT NULL DEFAULT 'popularity_v1',
  clicked_at     TIMESTAMPTZ,
  completed_at   TIMESTAMPTZ,
  PRIMARY KEY (user_id, content_id, created_at),
  CONSTRAINT recs_score_bounded CHECK (score BETWEEN 0 AND 1),
  CONSTRAINT recs_rank_bounded  CHECK (rank_position BETWEEN 1 AND 50),
  CONSTRAINT recs_reason_len    CHECK (length(reason_text) <= 200),
  CONSTRAINT recs_click_after_serve
    CHECK (clicked_at IS NULL OR clicked_at >= created_at),
  CONSTRAINT recs_complete_implies_clicked
    CHECK (completed_at IS NULL OR clicked_at IS NOT NULL),
  CONSTRAINT recs_complete_after_click
    CHECK (completed_at IS NULL OR completed_at >= clicked_at)
);

-- Hot path: render the user's served-history (most-recent-first).
CREATE INDEX idx_recs_user_recent
  ON plrs_ops.recommendations (user_id, created_at DESC);

-- Cohort analysis: filter by model variant, ordered most-recent-first.
CREATE INDEX idx_recs_variant
  ON plrs_ops.recommendations (model_variant, created_at DESC);

-- CTR analytics: enumerate clicked rows without scanning unclicked ones.
CREATE INDEX idx_recs_clicked
  ON plrs_ops.recommendations (clicked_at)
  WHERE clicked_at IS NOT NULL;

COMMENT ON TABLE plrs_ops.recommendations IS
  'Served recommendation slate; one row per (user, content, served-at)';
COMMENT ON COLUMN plrs_ops.recommendations.score IS
  'Relevance score in [0, 1] at scale 4 (matches RecommendationScore)';
COMMENT ON COLUMN plrs_ops.recommendations.rank_position IS
  'Server-assigned position in the served slate (1..50)';
COMMENT ON COLUMN plrs_ops.recommendations.model_variant IS
  'Identifier of the recommender variant that produced this row';
