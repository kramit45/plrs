-- Traces to: §3.c.1.4 quiz_attempts, FR-20 (scoring + persistence).
--
-- Filename note: V11 (not V10 as the step prompt suggested) because V10
-- was used by the quiz_items + quiz_item_options migration in step 80.
--
-- FK type: user_id is UUID to match plrs_ops.users.id (UUID per V2).

CREATE TABLE plrs_ops.quiz_attempts (
  attempt_id     BIGSERIAL PRIMARY KEY,
  user_id        UUID         NOT NULL REFERENCES plrs_ops.users(id) ON DELETE CASCADE,
  content_id     BIGINT       NOT NULL REFERENCES plrs_ops.content(content_id) ON DELETE CASCADE,
  score          NUMERIC(5,2) NOT NULL,
  per_item_json  JSONB        NOT NULL,
  policy_version VARCHAR(20)  NOT NULL DEFAULT 'v1',
  attempted_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  CONSTRAINT quiz_attempts_score_bounded CHECK (score BETWEEN 0 AND 100)
);

CREATE INDEX idx_quiz_attempts_user
  ON plrs_ops.quiz_attempts (user_id, attempted_at DESC);
CREATE INDEX idx_quiz_attempts_content
  ON plrs_ops.quiz_attempts (content_id);

COMMENT ON TABLE plrs_ops.quiz_attempts IS
  'Quiz attempts; per_item_json holds {per_item: [...], topic_weights: {...}} as JSONB.';
