#!/usr/bin/env bash
# Chaos: stop the Python plrs-ml service mid-flight; assert the recommender
# still serves 200 (NFR-11 graceful-degradation fallback).
#
# Prerequisites:
#   - docker-compose stack reachable (postgres, redis, plrs-ml)
#   - JVM app running on http://localhost:8080 (mvn spring-boot:run with
#     PLRS_ML_BASE_URL=http://localhost:8000 + PLRS_ML_HMAC_SECRET=dev-secret)
#   - newman seed loaded (newman-student@example.com / StudentPass01)
#
# The JVM app is NOT started by this script — it expects you to have the
# spring-boot:run terminal already open. Composing the JVM in
# docker-compose is out of scope for the demo.
#
# Usage: ./test/chaos/ml_down.sh
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
[ -n "$TOKEN" ] && [ "$TOKEN" != "null" ] || fail "Login failed; check seed.sql is loaded"

say "Pre-check: ML up, /api/recommendations should be 200"
STATUS_UP=$(curl -s -o /dev/null -w "%{http_code}" \
            -H "Authorization: Bearer $TOKEN" \
            "$BASE_URL/api/recommendations?k=5")
[ "$STATUS_UP" = "200" ] || fail "Expected 200 with ML up, got $STATUS_UP"
pass "ML-up call returned 200"

say "Stopping plrs-ml"
docker compose stop plrs-ml
sleep 3

say "Post-check: ML down, /api/recommendations should still be 200 (fallback)"
STATUS_DOWN=$(curl -s -o /dev/null -w "%{http_code}" \
              -H "Authorization: Bearer $TOKEN" \
              "$BASE_URL/api/recommendations?k=5")
if [ "$STATUS_DOWN" = "200" ]; then
  pass "ML-down call returned 200 — NFR-11 fallback verified"
else
  say "Bringing plrs-ml back up before exiting"
  docker compose start plrs-ml
  fail "Expected 200 with ML down, got $STATUS_DOWN"
fi

say "Restoring plrs-ml"
docker compose start plrs-ml
pass "Done."
