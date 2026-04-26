-- Consolidated seed for the full-regression Newman collection. Loads
-- the per-iteration seeds in order so that the four folders in
-- plrs-full-regression.postman_collection.json have all the rows they
-- need (instructor, admin, lockee, demo topic+quiz, path-target topic).
--
-- Idempotent — every per-iter seed uses ON CONFLICT DO NOTHING.
--
-- Why one wrapper file: simpler operator surface (one psql -f call)
-- and keeps the per-iter seed files canonical so they don't drift
-- from per-iter Newman runs.

\echo '--- seeding base (instructor + demo topic + demo quiz) ---'
\i seed.sql

\echo '--- seeding iter3 (admin user) ---'
\i seed-iter3.sql

\echo '--- seeding iter4 (lockee + path-target topic) ---'
\i seed-iter4.sql

\echo 'full-regression seed complete'
