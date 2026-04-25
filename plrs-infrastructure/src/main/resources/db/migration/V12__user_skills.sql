-- Traces to: §3.c.1.4 user_skills, §3.b.5.3 (TRG-3 monotonic
-- user_skills_version), §3.c.5.7 (EWMA), FR-21.
--
-- Filename note: V12 (not V11 as the step prompt suggested) because V11
-- was used by the quiz_attempts migration in step 84.

CREATE TABLE plrs_ops.user_skills (
  user_id       UUID         NOT NULL REFERENCES plrs_ops.users(id) ON DELETE CASCADE,
  topic_id      BIGINT       NOT NULL REFERENCES plrs_ops.topics(topic_id) ON DELETE CASCADE,
  mastery_score NUMERIC(4,3) NOT NULL,
  confidence    NUMERIC(4,3) NOT NULL DEFAULT 0.100,
  updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  PRIMARY KEY (user_id, topic_id),
  CONSTRAINT user_skills_mastery_bounded    CHECK (mastery_score BETWEEN 0 AND 1),
  CONSTRAINT user_skills_confidence_bounded CHECK (confidence BETWEEN 0 AND 1)
);

CREATE INDEX idx_user_skills_topic
  ON plrs_ops.user_skills (topic_id, mastery_score DESC);

-- Add user_skills_version to users (default 0; existing rows get 0).
ALTER TABLE plrs_ops.users
  ADD COLUMN user_skills_version BIGINT NOT NULL DEFAULT 0,
  ADD CONSTRAINT users_skills_ver_nn CHECK (user_skills_version >= 0);

-- TRG-3 (§3.b.5.3): user_skills_version is monotonic — never decreases.
CREATE OR REPLACE FUNCTION plrs_ops.fn_check_skills_version_monotonic()
RETURNS TRIGGER AS $$
BEGIN
  IF NEW.user_skills_version < OLD.user_skills_version THEN
    RAISE EXCEPTION
      'user_skills_version is monotonic; attempted % -> %',
      OLD.user_skills_version, NEW.user_skills_version
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END; $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_skills_version_monotonic
  BEFORE UPDATE OF user_skills_version ON plrs_ops.users
  FOR EACH ROW EXECUTE FUNCTION plrs_ops.fn_check_skills_version_monotonic();

COMMENT ON TABLE plrs_ops.user_skills IS
  'Per-user, per-topic mastery vector; updated by EWMA on quiz attempts (§3.c.5.7)';
COMMENT ON COLUMN plrs_ops.users.user_skills_version IS
  'Bumped atomically with user_skills upsert in TX-01; powers cache busts';
