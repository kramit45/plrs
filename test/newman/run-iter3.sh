#!/usr/bin/env bash
# Runs the Iter 3 E2E Postman collection via Newman against a running PLRS.
#
# Prerequisites:
#   - PLRS Postgres + Redis running, app on http://localhost:8080
#   - Newman installed (`npm i -g newman`)
#   - test/newman/seed.sql AND test/newman/seed-iter3.sql loaded
#   - (Optional) plrs-ml on http://localhost:8000 — without it the
#     /api/admin/eval/run step is treated as skipped (still passes).
#
# Usage:
#   ./run-iter3.sh                         # hits http://localhost:8080
#   BASE_URL=http://host:port ./run-iter3.sh
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080}"

newman run "$DIR/plrs-iter3.postman_collection.json" \
    -e "$DIR/plrs-iter3.postman_environment.json" \
    --env-var "baseUrl=$BASE_URL" \
    --reporters cli
