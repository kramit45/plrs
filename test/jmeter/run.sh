#!/usr/bin/env bash
# Run the JMeter recommendations-load scenario headless and produce an
# HTML report under test/jmeter/report/.
#
# Prerequisites:
#   - JMeter 5.6+ on PATH (`brew install jmeter` or download)
#   - PLRS app running on http://localhost:8080
#   - test/newman/seed.sql + seed-iter3.sql + seed-iter4.sql + seed-perf.sql loaded
#
# Usage:
#   ./run.sh
#   HOST=demo.plrs.example PORT=80 THREADS=50 DURATION=600 ./run.sh
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOST="${HOST:-localhost}"
PORT="${PORT:-8080}"
THREADS="${THREADS:-20}"
RAMP="${RAMP:-600}"
DURATION="${DURATION:-1200}"

rm -rf "$DIR/report" "$DIR/results.jtl"

jmeter -n -t "$DIR/recommendations-load.jmx" \
       -l "$DIR/results.jtl" \
       -e -o "$DIR/report" \
       -Jhost="$HOST" -Jport="$PORT" \
       -Jthreads="$THREADS" -Jramp="$RAMP" -Jduration="$DURATION"

echo
echo "Report: $DIR/report/index.html"
