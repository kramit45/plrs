# RB-1 — Restore the PLRS database from a backup

**Trigger:** prod database is corrupt, accidentally truncated, or lost
entirely. Use this runbook when the goal is to bring the live `plrs`
database back to the state captured in the most recent dump file.

**Pre-conditions:** a recent custom-format dump under `./backups/`
created by `scripts/backup.sh`, or restored from offsite storage. The
docker-compose Postgres container is reachable.

**Estimated time:** 2–10 min for a demo-sized DB (~1 MB dump). Scales
roughly linearly with dump size.

## Step 0 — Stop the writers

Bring the JVM app down so it stops issuing transactions against the
DB you are about to drop:

```bash
# In the terminal running mvn spring-boot:run — Ctrl-C
```

Stop the ETL worker and (optionally) Kafka so the outbox stops draining
into a DB that is in flux:

```bash
docker compose stop plrs-etl-worker
```

## Step 1 — Verify the dump is readable

Always do this against a scratch DB first — never restore directly over
the live one if you can help it:

```bash
./scripts/restore_verify.sh ./backups/plrs-YYYYMMDD-HHMMSS.dump
```

This script SHA256-checks the dump (if a sidecar exists), creates a
throwaway DB, runs `pg_restore` into it, and prints row counts. If it
exits 0 the dump is good. If it fails, pick an older dump.

## Step 2 — Replace the live DB

```bash
docker compose exec postgres psql -U plrs -d postgres -c \
  "DROP DATABASE plrs"
docker compose exec postgres psql -U plrs -d postgres -c \
  "CREATE DATABASE plrs"
docker compose exec -T postgres pg_restore --no-owner --no-privileges \
  -U plrs -d plrs < ./backups/plrs-YYYYMMDD-HHMMSS.dump
```

If you cannot drop because connections are open, terminate them first:

```sql
SELECT pg_terminate_backend(pid) FROM pg_stat_activity
 WHERE datname = 'plrs' AND pid <> pg_backend_pid();
```

## Step 3 — Re-apply Flyway baseline (only if the dump is older than HEAD)

`pg_restore` carries the schema as it was at dump time. If the codebase
has migrations newer than the dump, restart the JVM normally — Flyway
will see the existing `flyway_schema_history` table and apply the
missing forward migrations only:

```bash
mvn -pl plrs-web spring-boot:run
```

If Flyway reports a checksum mismatch (someone edited a migration file
in place after the dump was taken), DO NOT use `flyway repair` blindly
— escalate, because that means production schema and source schema
diverge.

## Step 4 — Smoke-check

```bash
curl -sf http://localhost:8080/health
curl -sX POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"newman-student@example.com","password":"StudentPass01"}'
```

If both succeed, restart `plrs-etl-worker`:

```bash
docker compose start plrs-etl-worker
```

## Step 5 — Communicate

Note the dump timestamp used and the data loss window (= last good dump
→ time of incident) in the incident channel. Anything happening after
the dump's timestamp is unrecoverable from this path; for that, see
**RB-2 (PITR)**.
