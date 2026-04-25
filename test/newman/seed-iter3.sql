-- Seed data required by the Iter 3 Newman collection
-- (test/newman/plrs-iter3.*). Idempotent — re-runnable.
--
-- Adds an ADMIN user on top of whatever the Iter 2 seed already
-- inserted (it must be loaded first; this script does not reseed
-- the instructor or the demo quiz). Stable UUID + BCrypt(12) hash
-- of "AdminPass01" so the collection can log in deterministically.

BEGIN;

INSERT INTO plrs_ops.users
    (id, email, password_hash, created_at, updated_at, created_by)
VALUES
    ('99999999-9999-9999-9999-999999999992',
     'newman-admin@example.com',
     '$2b$12$ugnHmpA0.P.D.duNnxF7vO42FPerFzWg4nqpaBzBsV7dbEwR36yFG',
     NOW(), NOW(), 'newman-iter3-seed')
ON CONFLICT (id) DO NOTHING;

INSERT INTO plrs_ops.user_roles (user_id, role, assigned_at)
VALUES ('99999999-9999-9999-9999-999999999992', 'ADMIN', NOW())
ON CONFLICT (user_id, role) DO NOTHING;

COMMIT;
