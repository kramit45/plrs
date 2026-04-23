# PowerShell runner for the Iter 1 E2E Postman collection via Newman.
# Assumes Node.js 20+ and Newman are installed (`npm i -g newman`).
#
# Usage:
#   .\run.ps1                                  # hits http://localhost:8080
#   $env:BASE_URL = "http://host:port"; .\run.ps1

$ErrorActionPreference = "Stop"
$DIR = Split-Path -Parent $MyInvocation.MyCommand.Path
$BASE_URL = if ($env:BASE_URL) { $env:BASE_URL } else { "http://localhost:8080" }

newman run (Join-Path $DIR "plrs-iter1.postman_collection.json") `
    -e (Join-Path $DIR "plrs-iter1.postman_environment.json") `
    --env-var "baseUrl=$BASE_URL" `
    --reporters cli
