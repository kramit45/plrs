-- Traces to: §3.c.1.3 content + content_tags, FR-08 (content catalogue),
-- FR-13 (paginated keyword search via GIN full-text index).
-- Owning aggregate: com.plrs.domain.content.Content. Column widths mirror
-- the domain validation rules (title 200, tag 60, est_minutes [1,600],
-- ctype/difficulty enum widths with headroom for future values).
--
-- FK typing note: created_by is UUID to match plrs_ops.users.id (UUID per
-- V2__users.sql). The step's literal SQL had BIGINT, which would be a
-- type mismatch and reject migration. See iter2 deviation log.

CREATE TABLE plrs_ops.content (
  content_id   BIGSERIAL    PRIMARY KEY,
  topic_id     BIGINT       NOT NULL REFERENCES plrs_ops.topics(topic_id) ON DELETE RESTRICT,
  title        VARCHAR(200) NOT NULL,
  ctype        VARCHAR(15)  NOT NULL,
  difficulty   VARCHAR(15)  NOT NULL,
  est_minutes  INT          NOT NULL,
  url          TEXT         NOT NULL,
  description  TEXT,
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  created_by   UUID         REFERENCES plrs_ops.users(id) ON DELETE SET NULL,
  updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  CONSTRAINT content_ctype_enum       CHECK (ctype      IN ('VIDEO','ARTICLE','EXERCISE','QUIZ')),
  CONSTRAINT content_difficulty_enum  CHECK (difficulty IN ('BEGINNER','INTERMEDIATE','ADVANCED')),
  CONSTRAINT content_est_bounded      CHECK (est_minutes BETWEEN 1 AND 600),
  CONSTRAINT content_title_nn         CHECK (length(trim(title)) > 0),
  CONSTRAINT content_url_scheme       CHECK (url ~* '^https?://'),
  CONSTRAINT content_topic_title_uk   UNIQUE (topic_id, title)
);

CREATE INDEX idx_content_topic   ON plrs_ops.content(topic_id);
CREATE INDEX idx_content_ctype   ON plrs_ops.content(ctype);
CREATE INDEX idx_content_created ON plrs_ops.content(created_at DESC);
CREATE INDEX idx_content_search  ON plrs_ops.content
  USING gin (to_tsvector('english', title || ' ' || coalesce(description, '')));

CREATE TABLE plrs_ops.content_tags (
  content_id BIGINT       NOT NULL REFERENCES plrs_ops.content(content_id) ON DELETE CASCADE,
  tag        VARCHAR(60)  NOT NULL,
  PRIMARY KEY (content_id, tag),
  CONSTRAINT tag_nn CHECK (length(trim(tag)) > 0)
);

CREATE INDEX idx_content_tags_tag ON plrs_ops.content_tags(tag);

COMMENT ON TABLE plrs_ops.content IS 'Central catalogue table; FR-08';
COMMENT ON INDEX plrs_ops.idx_content_search IS 'GIN tsvector for FR-13 keyword search';
COMMENT ON COLUMN plrs_ops.content.created_by IS 'FK to users.id (UUID); NULL for system-seeded content or after creator deletion';
