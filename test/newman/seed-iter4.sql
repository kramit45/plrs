-- Seed data required by the Iter 4 Newman collection
-- (test/newman/plrs-iter4.*). Idempotent — re-runnable.
--
-- Builds on seed.sql + seed-iter3.sql (instructor + admin already present).
-- Adds:
--   - Stable lockout-victim user (separate from the demo student so the
--     lockout tests can run repeatedly without breaking other flows)
--   - A path-target topic with two non-quiz content items so the path
--     planner has something to plan over

BEGIN;

-- Lockee for the FR-06 lockout test path.
-- BCrypt(12) of "LockeePass01"
INSERT INTO plrs_ops.users
    (id, email, password_hash, created_at, updated_at, created_by)
VALUES
    ('99999999-9999-9999-9999-999999999993',
     'newman-lockee@example.com',
     '$2b$12$ugnHmpA0.P.D.duNnxF7vO42FPerFzWg4nqpaBzBsV7dbEwR36yFG',
     NOW(), NOW(), 'newman-iter4-seed')
ON CONFLICT (id) DO NOTHING;

INSERT INTO plrs_ops.user_roles (user_id, role, assigned_at)
VALUES ('99999999-9999-9999-9999-999999999993', 'STUDENT', NOW())
ON CONFLICT (user_id, role) DO NOTHING;

-- Iter 4 path-target topic + two non-quiz items.
INSERT INTO plrs_ops.topics (topic_id, topic_name, description, created_by)
VALUES (900100, 'Iter4 Path Target', 'Newman Iter 4 path-planner target', 'newman-iter4-seed')
ON CONFLICT (topic_id) DO NOTHING;

INSERT INTO plrs_ops.content
    (content_id, topic_id, title, ctype, difficulty, est_minutes, url, description)
VALUES
    (900110, 900100, 'Iter4 Path Item A', 'ARTICLE', 'BEGINNER', 5,
     'https://example.com/iter4/a', 'Iter 4 path-target item A'),
    (900111, 900100, 'Iter4 Path Item B', 'ARTICLE', 'BEGINNER', 7,
     'https://example.com/iter4/b', 'Iter 4 path-target item B')
ON CONFLICT (content_id) DO NOTHING;

COMMIT;
