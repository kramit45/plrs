#!/usr/bin/env bash
# Chaos: stop Redis mid-flight; assert /api/recommendations still serves
# 200 via the DB-backed fallback (NFR-11). Top-N cache misses on every
# call without Redis but the slate is still computable.
#
# Prerequisites: docker-compose stack up, JVM app on http://localhost:8080,
# newman-student user seeded.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
EMAIL="${EMAIL:-newman-student@example.com}"
PASSWORD="${PASSWORD:-StudentPass01}"

say() { printf '\n\033[1;34m▶ %s\033[0m\n' "$*"; }
fail() { printf '\033[1;31m✗ %s\033[0m\n' "$*"; exit 1; }
pass() { printf '\033[1;32m✓ %s\033[0m\n' "$*"; }

say "Waiting for app /health"
for i in $(seq 1 30); do
  if curl -sf "$BASE_URL/health" >/dev/null; then break; fi
  [ "$i" = "30" ] && fail "App not reachable at $BASE_URL"
  sleep 1
done

say "Logging in as $EMAIL"
TOKEN=$(curl -sX POST "$BASE_URL/api/auth/login" \
        -H 'Content-Type: application/json' \
        -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" \
        | jq -r .accessToken)
[ -n "$TOKEN" ] && [ "$TOKEN" != "null" ] || fail "Login failed"

say "Stopping Redis"
docker compose stop redis
sleep 3

say "Post-check: Redis down, /api/recommendations should still be 200"
STATUS_DOWN=$(curl -s -o /dev/null -w "%{http_code}" \
              -H "Authorization: Bearer $TOKEN" \
              "$BASE_URL/api/recommendations?k=5")
if [ "$STATUS_DOWN" = "200" ]; then
  pass "Redis-down call returned 200 — DB-backed fallback works"
else
  say "Bringing Redis back up before exiting"
  docker compose start redis
  fail "Expected 200 with Redis down, got $STATUS_DOWN"
fi

say "Restoring Redis"
docker compose start redis
pass "Done."
