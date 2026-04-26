#!/usr/bin/env bash
# pg_dump-based logical backup of the plrs database. Writes a custom-format
# dump (-Fc) so that pg_restore can do partial / parallel restores. Custom
# format is also smaller than plain SQL and includes table data + DDL +
# large objects in one file.
#
# Output: $BACKUP_DIR/plrs-YYYYMMDD-HHMMSS.dump (+ .sha256 alongside).
# Retention: keeps the most recent $BACKUP_RETAIN files, deletes older.
#
# Usage:
#   ./scripts/backup.sh                # backs up via docker compose exec
#   PGURL=postgres://... ./scripts/backup.sh   # backs up a remote DB
#
# Prerequisites: docker compose stack running (default), or pg_dump
# installed locally with PGURL pointing at a reachable Postgres.
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-./backups}"
BACKUP_RETAIN="${BACKUP_RETAIN:-7}"
DB_NAME="${DB_NAME:-plrs}"
DB_USER="${DB_USER:-plrs}"

say() { printf '\n\033[1;34m▶ %s\033[0m\n' "$*"; }
fail() { printf '\033[1;31m✗ %s\033[0m\n' "$*"; exit 1; }
pass() { printf '\033[1;32m✓ %s\033[0m\n' "$*"; }

mkdir -p "$BACKUP_DIR"
TS=$(date -u +%Y%m%d-%H%M%S)
OUT="$BACKUP_DIR/plrs-${TS}.dump"

say "Dumping $DB_NAME → $OUT"
if [ -n "${PGURL:-}" ]; then
  pg_dump -Fc -d "$PGURL" -f "$OUT"
else
  # Stream out of the container — avoids needing pg_dump on the host.
  docker compose exec -T postgres pg_dump -Fc -U "$DB_USER" "$DB_NAME" > "$OUT"
fi

# Sanity: pg_dump custom format starts with "PGDMP".
head -c 5 "$OUT" | grep -q '^PGDMP' || fail "Output does not look like a custom-format dump"

SIZE=$(wc -c < "$OUT")
[ "$SIZE" -gt 1024 ] || fail "Dump is suspiciously small ($SIZE bytes)"

# Per-file SHA256 so the operator can verify integrity at restore time.
if command -v shasum >/dev/null 2>&1; then
  shasum -a 256 "$OUT" > "$OUT.sha256"
elif command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$OUT" > "$OUT.sha256"
fi

pass "Backup complete: $OUT ($SIZE bytes)"

# Retention: keep the newest $BACKUP_RETAIN .dump files, drop the rest
# (including their .sha256 sidecars).
say "Pruning to keep newest $BACKUP_RETAIN backups"
mapfile -t TO_DELETE < <(ls -1t "$BACKUP_DIR"/plrs-*.dump 2>/dev/null | tail -n +"$((BACKUP_RETAIN + 1))")
for f in "${TO_DELETE[@]}"; do
  rm -f "$f" "$f.sha256"
  echo "  removed $f"
done

pass "Done."
