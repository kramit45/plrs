-- Seed data required by the Iter 2 Newman collection (test/newman/plrs-iter2.*).
-- Idempotent: re-runnable against the same database without manual cleanup.
--
-- What this seeds:
--   1. An INSTRUCTOR user (newman-instructor@example.com / InstructorPass01)
--      with a real BCrypt(12) hash. The application's normal /api/auth/register
--      flow only assigns ROLE_STUDENT, so an instructor must be seeded here.
--   2. A demo topic + a demo QUIZ content with one item and two options
--      (option 1 is the correct answer). Iter 2 has no /api/content/quiz
--      authoring endpoint (step 81 was skipped), so the seeded quiz is what
--      the student attempt request POSTs against.
--
-- Stable IDs (instructor UUID, topic id, quiz id) make the collection
-- portable across environments. The BIGSERIAL sequences are advanced past
-- the seeded ids at the end so the live API can keep allocating without
-- collisions.

BEGIN;

-- 1. Instructor user.
INSERT INTO plrs_ops.users
    (id, email, password_hash, created_at, updated_at, created_by)
VALUES
    ('99999999-9999-9999-9999-999999999991',
     'newman-instructor@example.com',
     '$2b$12$94v4.rc/f586RUFRguNTROCpJOc85sZewnYh.H2CIWLaN0Rhyswam',
     NOW(), NOW(), 'newman-seed')
ON CONFLICT (id) DO NOTHING;

INSERT INTO plrs_ops.user_roles (user_id, role, assigned_at)
VALUES ('99999999-9999-9999-9999-999999999991', 'INSTRUCTOR', NOW())
ON CONFLICT (user_id, role) DO NOTHING;

-- 2. Demo topic with stable id 900001.
INSERT INTO plrs_ops.topics (topic_id, topic_name, description, created_by)
VALUES (900001, 'Newman Demo Topic',
        'Topic seeded for Iter 2 Newman collection',
        'newman-seed')
ON CONFLICT (topic_id) DO NOTHING;

-- 3. Demo quiz content with stable id 900002.
INSERT INTO plrs_ops.content
    (content_id, topic_id, title, ctype, difficulty,
     est_minutes, url, description, created_at, created_by, updated_at)
VALUES
    (900002, 900001, 'Newman Demo Quiz', 'QUIZ', 'BEGINNER',
     5, 'https://example.com/newman-quiz',
     'Quiz seeded for Iter 2 Newman collection',
     NOW(),
     '99999999-9999-9999-9999-999999999991',
     NOW())
ON CONFLICT (content_id) DO NOTHING;

-- 4. One quiz item under the demo quiz.
INSERT INTO plrs_ops.quiz_items
    (content_id, item_order, topic_id, stem, explanation)
VALUES
    (900002, 1, 900001, 'Newman demo: pick option A', 'A is correct')
ON CONFLICT (content_id, item_order) DO NOTHING;

-- 5. Two options on item 1; option 1 is correct.
INSERT INTO plrs_ops.quiz_item_options
    (content_id, item_order, option_order, option_text, is_correct)
VALUES
    (900002, 1, 1, 'Option A (correct)', TRUE),
    (900002, 1, 2, 'Option B (wrong)',   FALSE)
ON CONFLICT (content_id, item_order, option_order) DO NOTHING;

-- 6. Advance sequences so live API allocation stays clear of seed ids.
SELECT setval(pg_get_serial_sequence('plrs_ops.topics',  'topic_id'),
              GREATEST(900001, (SELECT COALESCE(MAX(topic_id),  0) FROM plrs_ops.topics)));
SELECT setval(pg_get_serial_sequence('plrs_ops.content', 'content_id'),
              GREATEST(900002, (SELECT COALESCE(MAX(content_id), 0) FROM plrs_ops.content)));

COMMIT;
