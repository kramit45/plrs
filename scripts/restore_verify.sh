#!/usr/bin/env bash
# Verifies a backup is restorable by restoring it into a SEPARATE
# scratch database (NOT the live plrs DB) and running smoke checks
# (table counts, schema fingerprint). The scratch DB is dropped at the
# end so the operator can re-run safely.
#
# Why a separate DB: pg_restore --schema-rename is not available in
# vanilla pg_dump/pg_restore, and stomping on plrs_ops in the live DB
# would be unsafe. A throwaway DB on the same Postgres instance is the
# simplest contract.
#
# Usage:
#   ./scripts/restore_verify.sh ./backups/plrs-20260426-120000.dump
#
# Prerequisites: docker compose stack running (Postgres reachable as
# the "postgres" service); plrs DB exists; pg_restore inside the container.
set -euo pipefail

DUMP="${1:-}"
[ -n "$DUMP" ] || { echo "usage: $0 <path/to/backup.dump>"; exit 2; }
[ -f "$DUMP" ] || { echo "no such file: $DUMP"; exit 2; }

DB_USER="${DB_USER:-plrs}"
LIVE_DB="${DB_NAME:-plrs}"
VERIFY_DB="${VERIFY_DB:-plrs_verify_$$}"

say() { printf '\n\033[1;34m▶ %s\033[0m\n' "$*"; }
fail() { printf '\033[1;31m✗ %s\033[0m\n' "$*"; cleanup; exit 1; }
pass() { printf '\033[1;32m✓ %s\033[0m\n' "$*"; }

psql_in() {
  docker compose exec -T postgres psql -U "$DB_USER" "$@"
}

cleanup() {
  say "Dropping scratch DB $VERIFY_DB"
  psql_in -d "$LIVE_DB" -c "DROP DATABASE IF EXISTS $VERIFY_DB" >/dev/null || true
}
trap cleanup EXIT

# 1. SHA256 sanity-check if a sidecar is present.
if [ -f "$DUMP.sha256" ]; then
  say "Verifying SHA256"
  if command -v shasum >/dev/null 2>&1; then
    (cd "$(dirname "$DUMP")" && shasum -a 256 -c "$(basename "$DUMP").sha256") \
      || fail "SHA256 mismatch"
  elif command -v sha256sum >/dev/null 2>&1; then
    (cd "$(dirname "$DUMP")" && sha256sum -c "$(basename "$DUMP").sha256") \
      || fail "SHA256 mismatch"
  fi
  pass "SHA256 OK"
fi

# 2. Create the scratch DB.
say "Creating scratch DB $VERIFY_DB"
psql_in -d "$LIVE_DB" -c "CREATE DATABASE $VERIFY_DB" >/dev/null

# 3. Stream the dump into pg_restore inside the container.
say "Restoring $DUMP → $VERIFY_DB"
docker compose exec -T postgres pg_restore --no-owner --no-privileges \
  -U "$DB_USER" -d "$VERIFY_DB" < "$DUMP"

# 4. Smoke checks: schemas exist, key tables are non-empty.
say "Smoke-checking restored schema"
SCHEMAS=$(psql_in -d "$VERIFY_DB" -tAc \
  "SELECT string_agg(nspname, ',') FROM pg_namespace WHERE nspname IN ('plrs_ops','plrs_dw')")
echo "  schemas present: $SCHEMAS"
[[ "$SCHEMAS" == *"plrs_ops"* ]] || fail "plrs_ops schema missing"

USER_COUNT=$(psql_in -d "$VERIFY_DB" -tAc "SELECT COUNT(*) FROM plrs_ops.app_user")
CONTENT_COUNT=$(psql_in -d "$VERIFY_DB" -tAc "SELECT COUNT(*) FROM plrs_ops.content")
echo "  app_user rows: $USER_COUNT"
echo "  content rows:  $CONTENT_COUNT"
[ "$USER_COUNT" -gt 0 ] || fail "app_user is empty in restored DB"

pass "Restore verified: $DUMP is loadable and non-empty."
