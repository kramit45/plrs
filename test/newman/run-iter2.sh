#!/usr/bin/env bash
# Runs the Iter 2 E2E Postman collection via Newman against a running PLRS.
#
# Prerequisites:
#   - PLRS Postgres + Redis running, app on http://localhost:8080
#   - Newman installed (`npm i -g newman`)
#   - test/newman/seed.sql loaded into the DB (see README)
#
# Usage:
#   ./run-iter2.sh                         # hits http://localhost:8080
#   BASE_URL=http://host:port ./run-iter2.sh
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080}"

newman run "$DIR/plrs-iter2.postman_collection.json" \
    -e "$DIR/plrs-iter2.postman_environment.json" \
    --env-var "baseUrl=$BASE_URL" \
    --reporters cli
