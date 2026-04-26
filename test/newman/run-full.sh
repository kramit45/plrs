#!/usr/bin/env bash
# Runs the consolidated full-regression Postman collection against a
# running PLRS. This is the single-shot pre-release regression — it
# walks every Iter 1..4 happy-path flow in one Newman invocation.
#
# Prerequisites:
#   - PLRS Postgres + Redis running, app on http://localhost:8080
#   - Newman installed (`npm i -g newman`)
#   - test/newman/seed-full.sql loaded (or run with --seed to load it)
#
# Usage:
#   ./run-full.sh                         # run against http://localhost:8080
#   BASE_URL=http://host:port ./run-full.sh
#   ./run-full.sh --seed                  # load seed-full.sql via psql first
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080}"

if [ "${1:-}" = "--seed" ]; then
  echo "▶ Loading seed-full.sql via docker compose"
  docker compose exec -T postgres psql -U plrs -d plrs < "$DIR/seed-full.sql"
fi

# Regenerate the consolidated collection if any per-iter source is newer.
if [ "$DIR/build-full-regression.sh" -nt "$DIR/plrs-full-regression.postman_collection.json" ] \
   || [ "$DIR/plrs-iter1.postman_collection.json" -nt "$DIR/plrs-full-regression.postman_collection.json" ] \
   || [ "$DIR/plrs-iter2.postman_collection.json" -nt "$DIR/plrs-full-regression.postman_collection.json" ] \
   || [ "$DIR/plrs-iter3.postman_collection.json" -nt "$DIR/plrs-full-regression.postman_collection.json" ] \
   || [ "$DIR/plrs-iter4.postman_collection.json" -nt "$DIR/plrs-full-regression.postman_collection.json" ]; then
  echo "▶ Per-iter collection changed — regenerating full-regression"
  "$DIR/build-full-regression.sh"
fi

newman run "$DIR/plrs-full-regression.postman_collection.json" \
    -e "$DIR/plrs-full-regression.postman_environment.json" \
    --env-var "baseUrl=$BASE_URL" \
    --reporters cli
