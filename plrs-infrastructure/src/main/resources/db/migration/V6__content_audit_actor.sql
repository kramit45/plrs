-- Traces to: §3.c.1.3 content + §3.a audit embeddable.
-- V5 shipped content with created_by UUID (FK to users.id, authoring user)
-- but without a column for the AuditFields actor-label string that every
-- other aggregate's audit trio carries. Step 58 embedded
-- AuditFieldsEmbeddable on ContentJpaEntity — that embeddable's createdBy
-- String property maps to column 'created_by' by default, colliding with
-- the UUID FK. Adding audit_created_by as a dedicated home for the actor
-- label, with @AttributeOverride on the entity, keeps the two concepts
-- separate without losing either.
--
-- DEFAULT 'system' so existing rows (if any) satisfy NOT NULL; the
-- embeddable-side contract still requires callers to supply an actor.

ALTER TABLE plrs_ops.content
  ADD COLUMN audit_created_by VARCHAR(64) NOT NULL DEFAULT 'system';

COMMENT ON COLUMN plrs_ops.content.audit_created_by IS
  'AuditFields actor label (e.g. "system", "admin-ui"); distinct from created_by which is the UUID FK to the authoring user';
