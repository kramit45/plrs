# Runs the Iter 3 E2E Postman collection via Newman against a running PLRS.
#
# Prerequisites:
#   - PLRS Postgres + Redis running, app on http://localhost:8080
#   - Newman installed (npm i -g newman)
#   - test/newman/seed.sql AND test/newman/seed-iter3.sql loaded
#   - (Optional) plrs-ml on http://localhost:8000 — without it the
#     /api/admin/eval/run step is treated as skipped (still passes).
#
# Usage:
#   .\run-iter3.ps1
#   $env:BASE_URL = "http://host:port"; .\run-iter3.ps1

$ErrorActionPreference = "Stop"

$dir = Split-Path -Parent $MyInvocation.MyCommand.Path
$baseUrl = if ($env:BASE_URL) { $env:BASE_URL } else { "http://localhost:8080" }

newman run (Join-Path $dir "plrs-iter3.postman_collection.json") `
    -e (Join-Path $dir "plrs-iter3.postman_environment.json") `
    --env-var "baseUrl=$baseUrl" `
    --reporters cli
