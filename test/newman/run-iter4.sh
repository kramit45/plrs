#!/usr/bin/env bash
# Runs the Iter 4 E2E Postman collection via Newman against a running PLRS.
#
# Prerequisites:
#   - PLRS Postgres + Redis running, app on http://localhost:8080
#   - Newman installed (`npm i -g newman`)
#   - test/newman/seed.sql + seed-iter3.sql + seed-iter4.sql loaded
#
# Usage:
#   ./run-iter4.sh
#   BASE_URL=http://host:port ./run-iter4.sh
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080}"

newman run "$DIR/plrs-iter4.postman_collection.json" \
    -e "$DIR/plrs-iter4.postman_environment.json" \
    --env-var "baseUrl=$BASE_URL" \
    --reporters cli
