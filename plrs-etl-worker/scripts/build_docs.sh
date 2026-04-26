#!/usr/bin/env bash
# Generates HTML API docs for the plrs-etl-worker package using pdoc
# and writes them to docs/python/plrs-etl-worker/ in the repo root.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")"/.. && pwd)"
OUT="$(cd "$DIR/.."; pwd)/docs/python/plrs-etl-worker"

mkdir -p "$OUT"
poetry --directory "$DIR" run pdoc plrs_etl -o "$OUT" --no-show-source

echo "wrote $OUT/index.html"
