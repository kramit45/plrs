#!/usr/bin/env bash
# Runs the Iter 1 E2E Postman collection via Newman against a running PLRS.
# Assumes Node.js 20+ and Newman are installed (`npm i -g newman`).
#
# Usage:
#   ./run.sh                         # hits http://localhost:8080
#   BASE_URL=http://host:port ./run.sh
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080}"

newman run "$DIR/plrs-iter1.postman_collection.json" \
    -e "$DIR/plrs-iter1.postman_environment.json" \
    --env-var "baseUrl=$BASE_URL" \
    --reporters cli
