# RB-2 — Point-in-time recovery (PITR) walkthrough

**Scope:** this runbook describes how PITR *would* be performed on a
managed Postgres instance (RDS, Cloud SQL, Crunchy Bridge, etc.). The
demo docker-compose stack does NOT have continuous WAL archiving
enabled, so PITR is not available locally — `RB-1` (restore from
nightly dump) is the only recovery path here. This document is the
production playbook for a future deployment.

**When to use:** something destructive happened at a known instant
(`UPDATE` without `WHERE`, malicious DELETE, bad migration) and you
need to roll the DB back to the moment *before* it. Standard nightly
dumps lose hours of data; PITR can recover within seconds of the
incident, assuming WAL archiving has been on the entire time.

## Prerequisites (production)

1. WAL archiving enabled (`archive_mode = on`, `archive_command`
   shipping segments to durable, separate-zone storage — typically S3
   via `wal-g` or `pgbackrest`).
2. A base backup taken regularly (typically nightly). PITR walks WAL
   forward from the most recent base backup that predates the target
   time.
3. Knowledge of the **target time** in UTC, ideally to the second.

## Step 1 — Pin down the target time

Pull the timestamp of the offending statement out of:

- application logs (`Slf4j` logs include UTC timestamps),
- the audit log (`/admin/audit` UI or `plrs_ops.audit_event`),
- `pg_stat_statements` if the bad query was DDL/DML.

Pick a target time **a few seconds before** the offending statement —
PITR replays forward to but not past your `recovery_target_time`.

## Step 2 — Provision a recovery target

Never PITR over the live DB. The standard path is:

1. Spin up a new DB instance from the base backup that immediately
   precedes your target time. On RDS this is "restore to point in
   time" with the timestamp filled in; the operator picks an
   instance class and a fresh DB identifier.
2. Wait for the recovery to complete (status goes from
   `creating` → `available`). The console shows WAL replay progress.
3. Connect with `psql` and verify the bad change is absent:
   ```sql
   SELECT * FROM plrs_ops.app_user WHERE id = <victim_id>;
   ```

## Step 3 — Cut over

Two patterns, pick based on data divergence after the incident:

- **No good writes since the incident** (rare — usually the case for
  ransomware or full-table truncation): repoint the application's
  connection string at the new instance, decommission the old one.
- **Good writes happened after the incident** (common — only one user
  or table was affected): export only the affected rows from the
  recovered instance with `COPY ... TO`, then `INSERT ... ON CONFLICT
  DO UPDATE` them back into the live DB. Leave the bulk of the live
  data alone.

## Step 4 — Communicate and tag

Record in the incident postmortem:

- target time (UTC),
- which path was used (full cutover vs. selective merge),
- which tables were touched,
- whether any user-visible state was lost between the incident and
  recovery (e.g., a few quiz attempts).

## Local fallback

Because the demo stack has no WAL archive, PITR is not possible
locally — operator must follow `RB-1` and accept the data loss window
back to the most recent nightly dump.
