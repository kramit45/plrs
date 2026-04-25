# PowerShell runner for the Iter 2 E2E Postman collection via Newman.
#
# Prerequisites:
#   - PLRS Postgres + Redis running, app on http://localhost:8080
#   - Newman installed: `npm i -g newman`
#   - test/newman/seed.sql loaded into the DB (see README)
#
# Usage:
#   .\run-iter2.ps1                                  # hits http://localhost:8080
#   $env:BASE_URL = "http://host:port"; .\run-iter2.ps1

$ErrorActionPreference = "Stop"
$DIR = Split-Path -Parent $MyInvocation.MyCommand.Path
$BASE_URL = if ($env:BASE_URL) { $env:BASE_URL } else { "http://localhost:8080" }

newman run (Join-Path $DIR "plrs-iter2.postman_collection.json") `
    -e (Join-Path $DIR "plrs-iter2.postman_environment.json") `
    --env-var "baseUrl=$BASE_URL" `
    --reporters cli
