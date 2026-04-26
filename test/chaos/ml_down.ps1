# Chaos: stop plrs-ml mid-flight; assert NFR-11 graceful degradation.
#
# Prerequisites: docker-compose stack up, JVM app on http://localhost:8080
# started with PLRS_ML_BASE_URL=http://localhost:8000 + HMAC secret, and
# the newman-student user seeded.
$ErrorActionPreference = 'Stop'

$BaseUrl  = if ($env:BASE_URL) { $env:BASE_URL } else { 'http://localhost:8080' }
$Email    = if ($env:EMAIL)    { $env:EMAIL }    else { 'newman-student@example.com' }
$Password = if ($env:PASSWORD) { $env:PASSWORD } else { 'StudentPass01' }

function Say  ($m) { Write-Host "▶ $m" -ForegroundColor Blue }
function Pass ($m) { Write-Host "✓ $m" -ForegroundColor Green }
function Fail ($m) { Write-Host "✗ $m" -ForegroundColor Red; exit 1 }

Say "Waiting for app /health"
$ok = $false
for ($i = 0; $i -lt 30; $i++) {
    try { Invoke-RestMethod "$BaseUrl/health" -ErrorAction Stop | Out-Null; $ok = $true; break }
    catch { Start-Sleep -Seconds 1 }
}
if (-not $ok) { Fail "App not reachable at $BaseUrl" }

Say "Logging in as $Email"
$body = @{ email = $Email; password = $Password } | ConvertTo-Json
$resp = Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post `
    -ContentType 'application/json' -Body $body
$Token = $resp.accessToken
if (-not $Token) { Fail "Login failed; check seed.sql is loaded" }

Say "Pre-check: ML up, /api/recommendations should be 200"
$r = Invoke-WebRequest "$BaseUrl/api/recommendations?k=5" `
    -Headers @{ Authorization = "Bearer $Token" } -UseBasicParsing
if ($r.StatusCode -ne 200) { Fail "Expected 200 with ML up, got $($r.StatusCode)" }
Pass "ML-up call returned 200"

Say "Stopping plrs-ml"
docker compose stop plrs-ml | Out-Null
Start-Sleep -Seconds 3

Say "Post-check: ML down, /api/recommendations should still be 200 (fallback)"
try {
    $r = Invoke-WebRequest "$BaseUrl/api/recommendations?k=5" `
        -Headers @{ Authorization = "Bearer $Token" } -UseBasicParsing
    $status = $r.StatusCode
} catch {
    $status = $_.Exception.Response.StatusCode.value__
}
if ($status -eq 200) {
    Pass "ML-down call returned 200 — NFR-11 fallback verified"
} else {
    Say "Bringing plrs-ml back up before exiting"
    docker compose start plrs-ml | Out-Null
    Fail "Expected 200 with ML down, got $status"
}

Say "Restoring plrs-ml"
docker compose start plrs-ml | Out-Null
Pass "Done."
