#!/usr/bin/env bash
# Chaos: stop Kafka, take a quiz attempt, watch the outbox accumulate;
# restart Kafka, watch the drain catch up. Verifies NFR-11 graceful
# degradation for the outbox path.
#
# Prerequisites: docker-compose stack up with PLRS_KAFKA_ENABLED=true,
# JVM app on http://localhost:8080, seed.sql loaded.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
EMAIL="${EMAIL:-newman-student@example.com}"
PASSWORD="${PASSWORD:-StudentPass01}"
QUIZ_CONTENT_ID="${QUIZ_CONTENT_ID:-900002}"  # demo quiz from seed.sql

say() { printf '\n\033[1;34m▶ %s\033[0m\n' "$*"; }
fail() { printf '\033[1;31m✗ %s\033[0m\n' "$*"; exit 1; }
pass() { printf '\033[1;32m✓ %s\033[0m\n' "$*"; }

undelivered() {
  docker compose exec -T postgres psql -U plrs -d plrs -tAc \
    "SELECT COUNT(*) FROM plrs_ops.outbox_event WHERE delivered_at IS NULL"
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

BASELINE=$(undelivered)
say "Baseline undelivered outbox rows: $BASELINE"

say "Stopping Kafka"
docker compose stop kafka
sleep 3

say "Submitting a quiz attempt while Kafka is down"
curl -s -X POST "$BASE_URL/api/quiz-attempts" \
     -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' \
     -d "{\"contentId\":$QUIZ_CONTENT_ID,\"answers\":{\"1\":1}}" \
     >/dev/null || true

# Give the publisher a chance to fail and leave delivered_at NULL.
sleep 5
WHILE_DOWN=$(undelivered)
say "Undelivered rows while Kafka down: $WHILE_DOWN"
if [ "$WHILE_DOWN" -le "$BASELINE" ]; then
  say "WARN: outbox didn't accumulate as expected ($BASELINE → $WHILE_DOWN)."
  say "      This may mean the publisher already drained or your stack"
  say "      doesn't have PLRS_KAFKA_ENABLED=true — continuing anyway."
fi

say "Restarting Kafka"
docker compose start kafka

say "Waiting up to 60s for the drain to catch up"
for i in $(seq 1 60); do
  CUR=$(undelivered)
  if [ "$CUR" -le "$BASELINE" ]; then
    pass "Outbox drained: $WHILE_DOWN → $CUR (baseline $BASELINE) in ${i}s"
    exit 0
  fi
  sleep 1
done

fail "Outbox did not drain back to baseline within 60s (still $CUR)"
