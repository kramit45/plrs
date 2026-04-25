-- Traces to: §3.c.1.4 interactions, FR-15 (view tracking), FR-16
-- (interaction events), FR-17 (rating), §3.b.7.2 (composite PK + 10-min
-- debounce — debounce lives in the use case, step 71).
--
-- Filename note: V8 (not V7 as the step prompt suggested) because V7 was
-- used by the prerequisites migration in step 61.
--
-- FK type note: user_id is UUID to match plrs_ops.users.id (UUID per
-- V2__users.sql). Same fix as content.created_by (V5) and
-- prerequisites.added_by (V7).

CREATE TABLE plrs_ops.interactions (
  user_id     UUID         NOT NULL REFERENCES plrs_ops.users(id) ON DELETE CASCADE,
  content_id  BIGINT       NOT NULL REFERENCES plrs_ops.content(content_id) ON DELETE CASCADE,
  occurred_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  event_type  VARCHAR(20)  NOT NULL,
  dwell_sec   INT,
  rating      INT,
  client_info VARCHAR(200),
  PRIMARY KEY (user_id, content_id, occurred_at),

  CONSTRAINT interactions_type_enum
    CHECK (event_type IN ('VIEW','COMPLETE','BOOKMARK','LIKE','RATE')),

  CONSTRAINT interactions_rating_iff_rate CHECK (
    (event_type = 'RATE' AND rating IS NOT NULL AND rating BETWEEN 1 AND 5)
    OR (event_type <> 'RATE' AND rating IS NULL)
  ),

  CONSTRAINT interactions_dwell_only_vc CHECK (
    (event_type IN ('VIEW','COMPLETE') AND (dwell_sec IS NULL OR dwell_sec >= 0))
    OR (event_type NOT IN ('VIEW','COMPLETE') AND dwell_sec IS NULL)
  )
);

-- Signal-aggregator read pattern (A1): recent N events per user
CREATE INDEX idx_interactions_user_recent
  ON plrs_ops.interactions (user_id, occurred_at DESC);

-- Per-content popularity counters
CREATE INDEX idx_interactions_content
  ON plrs_ops.interactions (content_id, occurred_at DESC);

-- ETL watermark scan
CREATE INDEX idx_interactions_occurred
  ON plrs_ops.interactions (occurred_at);

COMMENT ON TABLE plrs_ops.interactions IS
  'Implicit-feedback event stream. Three CHECK constraints enforce FR-15/16/17 field rules.';
