# Runs the consolidated full-regression Postman collection against a
# running PLRS. PowerShell sibling of run-full.sh.
#
# Prerequisites: PLRS Postgres + Redis running, app on http://localhost:8080,
# Newman installed (`npm i -g newman`), test/newman/seed-full.sql loaded.
#
# Usage:
#   .\run-full.ps1                        # against http://localhost:8080
#   $env:BASE_URL="http://host:port"; .\run-full.ps1
$ErrorActionPreference = 'Stop'

$Dir     = Split-Path -Parent $MyInvocation.MyCommand.Path
$BaseUrl = if ($env:BASE_URL) { $env:BASE_URL } else { 'http://localhost:8080' }

newman run "$Dir/plrs-full-regression.postman_collection.json" `
    -e "$Dir/plrs-full-regression.postman_environment.json" `
    --env-var "baseUrl=$BaseUrl" `
    --reporters cli
