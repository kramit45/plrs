-- Traces to: §3.c.1.3 prerequisites, FR-09 (prerequisite tracking),
-- §2.e.2.5 (CFD-4 cycle detection at application layer),
-- §3.b.5.5 (nightly recursive-CTE integrity check, deferred to Iter 4).
--
-- No trigger is installed on this table. Cycle detection lives in the
-- application layer (Content.canAddPrerequisite + a recursive walk on
-- the adapter side, step 62) with SERIALIZABLE isolation as the race
-- defence per §3.b.7.1. A nightly recursive-CTE integrity job ships in
-- Iter 4 to catch any pathological inserts that slip past.
--
-- Filename note: this is V7 (not V6) because V6 was used by the
-- content_audit_actor migration that forward-fixed step 58 in step 59.
--
-- FK type note: added_by is UUID to match plrs_ops.users.id (UUID per
-- V2__users.sql). The step's literal SQL had BIGINT, which would be a
-- type mismatch and reject migration. Same fix as step 57's content.created_by.

CREATE TABLE plrs_ops.prerequisites (
  content_id         BIGINT       NOT NULL REFERENCES plrs_ops.content(content_id) ON DELETE CASCADE,
  prereq_content_id  BIGINT       NOT NULL REFERENCES plrs_ops.content(content_id) ON DELETE CASCADE,
  added_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  added_by           UUID         REFERENCES plrs_ops.users(id) ON DELETE SET NULL,
  PRIMARY KEY (content_id, prereq_content_id),
  CONSTRAINT prereq_no_self CHECK (content_id <> prereq_content_id)
);

CREATE INDEX idx_prereq_of    ON plrs_ops.prerequisites(prereq_content_id);
CREATE INDEX idx_prereq_added ON plrs_ops.prerequisites(added_at DESC);

COMMENT ON TABLE plrs_ops.prerequisites IS
  'Prereq DAG. Cycle detection is application-level (§2.e.2.5); nightly integrity check per §3.b.5.5 runs a recursive CTE.';
COMMENT ON COLUMN plrs_ops.prerequisites.added_by IS
  'FK to users.id (UUID); NULL for system-seeded edges or after author deletion';
