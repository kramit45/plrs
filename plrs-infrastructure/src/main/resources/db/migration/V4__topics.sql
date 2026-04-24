-- Traces to: §3.c.1.3 topics, FR-07 (topic hierarchy).
-- Owning aggregate: com.plrs.domain.topic.Topic. Column widths mirror the
-- domain validation rules so the database refuses values the value objects
-- would also refuse: topic_name 120, description 500, created_by 64.
-- parent_topic_id is a self-referential FK; ON DELETE SET NULL promotes
-- orphans to roots rather than cascading, matching the Iter 2 policy that
-- deleting a parent does not delete its subtree. The partial index on
-- parent_topic_id skips the root-topic rows so the index stays small while
-- still accelerating "list children of N" lookups (§4.a.1.1).

CREATE TABLE plrs_ops.topics (
  topic_id        BIGSERIAL     PRIMARY KEY,
  topic_name      VARCHAR(120)  NOT NULL,
  description     VARCHAR(500),
  parent_topic_id BIGINT        REFERENCES plrs_ops.topics(topic_id) ON DELETE SET NULL,
  created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
  created_by      VARCHAR(64)   NOT NULL,
  CONSTRAINT topics_name_uk UNIQUE (topic_name),
  CONSTRAINT topics_name_nn CHECK (length(trim(topic_name)) > 0)
);

CREATE INDEX idx_topics_parent ON plrs_ops.topics(parent_topic_id)
  WHERE parent_topic_id IS NOT NULL;

COMMENT ON TABLE plrs_ops.topics IS 'Hierarchical subject catalogue; FR-07';
COMMENT ON COLUMN plrs_ops.topics.parent_topic_id IS 'Self-FK; NULL for root topics; ON DELETE SET NULL promotes orphans';
