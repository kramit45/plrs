-- Traces to: §3.c.1.4 learner_paths + steps, §3.b.4.3 (one active path per
-- user+target invariant), FR-31..FR-34.
--
-- Two tables backing the LearnerPath aggregate. The partial unique index
-- on (user_id, target_topic_id) WHERE status IN (active subset) enforces
-- §3.b.4.3 at the database boundary — the application-side TX-10
-- supersede-then-save sequence still relies on this constraint to detect
-- races.
--
-- Mastery snapshots are JSONB blobs because the keys are TopicIds; a
-- normalised side table would not survive the snapshot semantics
-- (point-in-time mastery must not change when the topic table changes).

CREATE TABLE plrs_ops.learner_paths (
  path_id                BIGSERIAL    PRIMARY KEY,
  user_id                UUID         NOT NULL REFERENCES plrs_ops.users(id) ON DELETE CASCADE,
  target_topic_id        BIGINT       NOT NULL REFERENCES plrs_ops.topics(topic_id) ON DELETE RESTRICT,
  status                 VARCHAR(20)  NOT NULL DEFAULT 'NOT_STARTED',
  generated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  started_at             TIMESTAMPTZ,
  paused_at              TIMESTAMPTZ,
  completed_at           TIMESTAMPTZ,
  abandoned_at           TIMESTAMPTZ,
  superseded_at          TIMESTAMPTZ,
  superseded_by          BIGINT       REFERENCES plrs_ops.learner_paths(path_id) ON DELETE SET NULL,
  mastery_start_snapshot JSONB        NOT NULL,
  mastery_end_snapshot   JSONB,
  CONSTRAINT learner_paths_status_enum CHECK (status IN
    ('NOT_STARTED','IN_PROGRESS','PAUSED','REVIEW_PENDING',
     'COMPLETED','ABANDONED','SUPERSEDED'))
);

-- §3.b.4.3 invariant: only one active path per (user, target). A learner
-- can have many historical (COMPLETED/ABANDONED/SUPERSEDED) paths for
-- the same target without conflict.
CREATE UNIQUE INDEX learner_paths_one_active
  ON plrs_ops.learner_paths (user_id, target_topic_id)
  WHERE status IN ('NOT_STARTED','IN_PROGRESS','PAUSED','REVIEW_PENDING');

CREATE INDEX idx_learner_paths_user
  ON plrs_ops.learner_paths (user_id, generated_at DESC);

COMMENT ON TABLE plrs_ops.learner_paths IS
  'FR-31 prerequisite-aware learner paths; one active per (user,target).';
COMMENT ON COLUMN plrs_ops.learner_paths.mastery_start_snapshot IS
  'JSONB { topicId(string) -> mastery(numeric) } captured at plan time.';
COMMENT ON COLUMN plrs_ops.learner_paths.mastery_end_snapshot IS
  'Same shape, captured at COMPLETED transition; NULL for non-completed.';

CREATE TABLE plrs_ops.learner_path_steps (
  path_id          BIGINT       NOT NULL REFERENCES plrs_ops.learner_paths(path_id) ON DELETE CASCADE,
  step_order       INT          NOT NULL,
  content_id       BIGINT       NOT NULL REFERENCES plrs_ops.content(content_id) ON DELETE RESTRICT,
  step_status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
  added_as_review  BOOLEAN      NOT NULL DEFAULT FALSE,
  reason_in_path   VARCHAR(200) NOT NULL,
  started_at       TIMESTAMPTZ,
  completed_at     TIMESTAMPTZ,
  PRIMARY KEY (path_id, step_order),
  CONSTRAINT steps_status_enum CHECK (step_status IN ('PENDING','IN_PROGRESS','DONE','SKIPPED')),
  CONSTRAINT steps_order_pos   CHECK (step_order > 0),
  CONSTRAINT steps_reason_len  CHECK (char_length(reason_in_path) BETWEEN 1 AND 200)
);

CREATE INDEX idx_learner_path_steps_content
  ON plrs_ops.learner_path_steps (content_id);

COMMENT ON TABLE plrs_ops.learner_path_steps IS
  'Ordered steps inside a learner_path; (path_id, step_order) is the natural PK.';
