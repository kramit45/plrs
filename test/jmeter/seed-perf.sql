-- Perf seed for the JMeter recommendations-load scenario.
-- Generates 50 students + 100 content items + ~5 000 interactions so the
-- recommender produces non-trivial slates. Idempotent — re-runnable.
--
-- Builds on top of seed.sql + seed-iter3.sql + seed-iter4.sql (the
-- newman-student / instructor / admin users must already exist).

BEGIN;

-- 50 perf-test students (UUIDs derived from a stable namespace so re-runs
-- collapse onto the same rows).
DO $$
DECLARE
  i INT;
  uid UUID;
BEGIN
  FOR i IN 1..50 LOOP
    uid := uuid_generate_v5(
      'a3c5e7d1-0000-0000-0000-000000000000'::uuid,
      'perf-student-' || i::text
    );
    INSERT INTO plrs_ops.users
        (id, email, password_hash, created_at, updated_at, created_by)
    VALUES
        (uid,
         'perf-student-' || i || '@example.com',
         '$2b$12$ugnHmpA0.P.D.duNnxF7vO42FPerFzWg4nqpaBzBsV7dbEwR36yFG',
         NOW(), NOW(), 'perf-seed')
    ON CONFLICT (id) DO NOTHING;
    INSERT INTO plrs_ops.user_roles (user_id, role, assigned_at)
    VALUES (uid, 'STUDENT', NOW())
    ON CONFLICT (user_id, role) DO NOTHING;
  END LOOP;
END $$;

-- A perf topic + 100 non-quiz items (stable IDs starting at 950000).
INSERT INTO plrs_ops.topics (topic_id, topic_name, description, created_by)
VALUES (950000, 'Perf Topic', 'JMeter perf-test topic', 'perf-seed')
ON CONFLICT (topic_id) DO NOTHING;

DO $$
DECLARE
  i INT;
BEGIN
  FOR i IN 1..100 LOOP
    INSERT INTO plrs_ops.content
        (content_id, topic_id, title, ctype, difficulty, est_minutes, url, description)
    VALUES
        (950000 + i, 950000,
         'Perf Item ' || i,
         CASE (i % 3) WHEN 0 THEN 'VIDEO' WHEN 1 THEN 'ARTICLE' ELSE 'EXERCISE' END,
         CASE (i % 3) WHEN 0 THEN 'BEGINNER' WHEN 1 THEN 'INTERMEDIATE' ELSE 'ADVANCED' END,
         5 + (i % 20),
         'https://example.com/perf/' || i,
         'Perf seed item ' || i)
    ON CONFLICT (content_id) DO NOTHING;
  END LOOP;
END $$;

-- ~5000 random VIEW interactions: each of the 50 students touches ~100 items.
DO $$
DECLARE
  s INT;
  c INT;
  uid UUID;
BEGIN
  FOR s IN 1..50 LOOP
    uid := uuid_generate_v5(
      'a3c5e7d1-0000-0000-0000-000000000000'::uuid,
      'perf-student-' || s::text
    );
    FOR c IN 1..100 LOOP
      INSERT INTO plrs_ops.interactions
          (user_id, content_id, event_type, occurred_at, dwell_sec)
      VALUES
          (uid, 950000 + c, 'VIEW',
           NOW() - (random() * INTERVAL '30 days'),
           30 + (random() * 270)::int)
      ON CONFLICT DO NOTHING;
    END LOOP;
  END LOOP;
END $$;

COMMIT;
