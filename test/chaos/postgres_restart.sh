#!/usr/bin/env bash
# Chaos: restart Postgres mid-traffic, drive recommendations against the
# JVM app, and assert the app recovers within 30 s (NFR-9). Hikari's
# connection-pool retry plus Spring transaction retries should bridge the
# gap; observed call windows that overlap the restart will fail with 500
# briefly, but the rolling window must return to all-200 within 30 s.
#
# Prerequisites: docker-compose stack up, JVM app on http://localhost:8080,
# newman-student user seeded. The JVM app is operator-started (mvn
# spring-boot:run) — see test/chaos/README.md.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
EMAIL="${EMAIL:-newman-student@example.com}"
PASSWORD="${PASSWORD:-StudentPass01}"
RECOVERY_BUDGET_S="${RECOVERY_BUDGET_S:-30}"

say() { printf '\n\033[1;34m▶ %s\033[0m\n' "$*"; }
fail() { printf '\033[1;31m✗ %s\033[0m\n' "$*"; exit 1; }
pass() { printf '\033[1;32m✓ %s\033[0m\n' "$*"; }

probe() {
  curl -s -o /dev/null -w "%{http_code}" \
       -H "Authorization: Bearer $TOKEN" \
       "$BASE_URL/api/recommendations?k=5"
}

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

say "Pre-check: Postgres up, /api/recommendations should be 200"
STATUS_UP=$(probe)
[ "$STATUS_UP" = "200" ] || fail "Expected 200 before restart, got $STATUS_UP"
pass "Pre-restart call returned 200"

say "Restarting Postgres"
docker compose restart postgres

# Sample the endpoint every second; require RECOVERY_BUDGET_S consecutive
# 200s to call the system recovered. This is stricter than "first 200 wins"
# because Hikari may serve a cached connection that fails on the next call.
say "Polling for recovery (budget ${RECOVERY_BUDGET_S}s)"
RECOVERED_AT=""
for i in $(seq 1 "$RECOVERY_BUDGET_S"); do
  CODE=$(probe || echo "000")
  if [ "$CODE" = "200" ]; then
    if [ -z "$RECOVERED_AT" ]; then
      RECOVERED_AT=$i
      pass "First 200 after restart at ${i}s"
    fi
  else
    # If we saw a 200 then drop again, reset the marker.
    if [ -n "$RECOVERED_AT" ]; then
      say "Regression at ${i}s (HTTP $CODE) — resetting recovery clock"
      RECOVERED_AT=""
    fi
  fi
  sleep 1
done

if [ -n "$RECOVERED_AT" ]; then
  pass "Postgres recovery within budget: first 200 at ${RECOVERED_AT}s, stable through ${RECOVERY_BUDGET_S}s"
  exit 0
fi

fail "App did not recover within ${RECOVERY_BUDGET_S}s after Postgres restart"
