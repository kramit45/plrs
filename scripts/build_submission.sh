#!/usr/bin/env bash
# Builds the IGNOU MCSP-232 submission zip
# (submission/plrs-submission-v1.0.0.zip) — single-file artefact uploaded
# to the LMS alongside report.pdf.
#
# What goes in:
#   - source.tar.gz    git archive of HEAD (tag the commit before running)
#   - javadoc/          mvn javadoc:aggregate output
#   - python-docs/     pdoc for plrs-ml + plrs-etl-worker
#   - plrs.jar          executable Spring Boot jar
#   - report.pdf        design report (if present at repo root)
#   - test-reports-newman/  optional, if a Newman run produced one
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")"/.. && pwd)"
cd "$ROOT"

VERSION="${VERSION:-v1.0.0}"
OUT_DIR="$ROOT/submission"
ZIP_NAME="plrs-submission-${VERSION}.zip"

say() { printf '\n\033[1;34m▶ %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m! %s\033[0m\n' "$*"; }

say "Cleaning $OUT_DIR"
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

say "Archiving source at HEAD as plrs-${VERSION}-source.tar.gz"
git archive --format=tar.gz \
    --prefix="plrs-${VERSION}/" \
    HEAD > "$OUT_DIR/plrs-${VERSION}-source.tar.gz"

if [ -f "$ROOT/report.pdf" ]; then
  say "Including report.pdf"
  cp "$ROOT/report.pdf" "$OUT_DIR/"
else
  warn "report.pdf not found at repo root — submission will lack the design report"
fi

say "Generating aggregated Javadoc → submission/javadoc/"
mvn -B -ntp javadoc:aggregate >/dev/null
if [ -d "$ROOT/docs/javadoc" ]; then
  cp -r "$ROOT/docs/javadoc" "$OUT_DIR/javadoc"
fi

say "Generating Python pdoc → submission/python-docs/"
mkdir -p "$OUT_DIR/python-docs"
if command -v poetry >/dev/null 2>&1; then
  bash "$ROOT/plrs-ml/scripts/build_docs.sh" || warn "plrs-ml pdoc failed"
  bash "$ROOT/plrs-etl-worker/scripts/build_docs.sh" || warn "plrs-etl-worker pdoc failed"
  if [ -d "$ROOT/docs/python/plrs-ml" ]; then
    cp -r "$ROOT/docs/python/plrs-ml" "$OUT_DIR/python-docs/plrs-ml"
  fi
  if [ -d "$ROOT/docs/python/plrs-etl-worker" ]; then
    cp -r "$ROOT/docs/python/plrs-etl-worker" "$OUT_DIR/python-docs/plrs-etl-worker"
  fi
else
  warn "poetry not installed — skipping Python pdoc"
fi

say "Packaging Spring Boot jar"
mvn -B -ntp -DskipTests package >/dev/null
cp plrs-web/target/plrs.jar "$OUT_DIR/" 2>/dev/null || warn "plrs.jar not produced"

# Bundle whatever Newman HTML reports happen to exist (CI emits one on
# failure; locally there's no default report dir).
if [ -d "$ROOT/test/newman/report" ]; then
  say "Including test/newman/report → submission/test-reports-newman/"
  cp -r "$ROOT/test/newman/report" "$OUT_DIR/test-reports-newman"
fi

say "Zipping → $ZIP_NAME"
(cd "$OUT_DIR" && zip -r "$ZIP_NAME" . -x "$ZIP_NAME" >/dev/null)

printf '\n\033[1;32m✓ Submission ready: %s\033[0m\n' "$OUT_DIR/$ZIP_NAME"
ls -lh "$OUT_DIR/$ZIP_NAME"
