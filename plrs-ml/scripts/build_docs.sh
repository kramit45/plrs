#!/usr/bin/env bash
# Generates HTML API docs for the plrs-ml package using pdoc and writes
# them to docs/python/plrs-ml/ in the repo root, alongside the Java
# Javadoc tree. pdoc is added to the dev group on first run.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")"/.. && pwd)"
OUT="$(cd "$DIR/.."; pwd)/docs/python/plrs-ml"

mkdir -p "$OUT"
poetry --directory "$DIR" run pdoc plrs_ml -o "$OUT" --no-show-source

echo "wrote $OUT/index.html"
