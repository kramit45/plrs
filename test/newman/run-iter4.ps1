# Runs the Iter 4 E2E Postman collection via Newman against a running PLRS.
#
# Prerequisites:
#   - PLRS Postgres + Redis running, app on http://localhost:8080
#   - Newman installed (`npm i -g newman`)
#   - test/newman/seed.sql + seed-iter3.sql + seed-iter4.sql loaded
#
# Usage:
#   .\run-iter4.ps1
#   $env:BASE_URL='http://host:port'; .\run-iter4.ps1
$ErrorActionPreference = 'Stop'
$dir = Split-Path -Parent $MyInvocation.MyCommand.Path
$baseUrl = if ($env:BASE_URL) { $env:BASE_URL } else { 'http://localhost:8080' }

newman run "$dir\plrs-iter4.postman_collection.json" `
    -e "$dir\plrs-iter4.postman_environment.json" `
    --env-var "baseUrl=$baseUrl" `
    --reporters cli
