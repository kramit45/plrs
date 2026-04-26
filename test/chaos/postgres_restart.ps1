# Chaos: restart Postgres mid-traffic; assert NFR-9 30 s recovery via the
# Hikari connection-pool retry path.
#
# Prerequisites: docker-compose stack up, JVM app on http://localhost:8080,
# newman-student user seeded. The JVM app is operator-started — see
# test/chaos/README.md.
$ErrorActionPreference = 'Stop'

$BaseUrl  = if ($env:BASE_URL) { $env:BASE_URL } else { 'http://localhost:8080' }
$Email    = if ($env:EMAIL)    { $env:EMAIL }    else { 'newman-student@example.com' }
$Password = if ($env:PASSWORD) { $env:PASSWORD } else { 'StudentPass01' }
$Budget   = if ($env:RECOVERY_BUDGET_S) { [int]$env:RECOVERY_BUDGET_S } else { 30 }

function Say  ($m) { Write-Host "▶ $m" -ForegroundColor Blue }
function Pass ($m) { Write-Host "✓ $m" -ForegroundColor Green }
function Fail ($m) { Write-Host "✗ $m" -ForegroundColor Red; exit 1 }

function Probe {
    try {
        $r = Invoke-WebRequest "$BaseUrl/api/recommendations?k=5" `
            -Headers @{ Authorization = "Bearer $Token" } -UseBasicParsing
        return [int]$r.StatusCode
    } catch {
        if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode.value__ }
        return 0
    }
}

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

Say "Pre-check: Postgres up, /api/recommendations should be 200"
$pre = Probe
if ($pre -ne 200) { Fail "Expected 200 before restart, got $pre" }
Pass "Pre-restart call returned 200"

Say "Restarting Postgres"
docker compose restart postgres | Out-Null

Say "Polling for recovery (budget ${Budget}s)"
$RecoveredAt = $null
for ($i = 1; $i -le $Budget; $i++) {
    $code = Probe
    if ($code -eq 200) {
        if (-not $RecoveredAt) {
            $RecoveredAt = $i
            Pass "First 200 after restart at ${i}s"
        }
    } else {
        if ($RecoveredAt) {
            Say "Regression at ${i}s (HTTP $code) — resetting recovery clock"
            $RecoveredAt = $null
        }
    }
    Start-Sleep -Seconds 1
}

if ($RecoveredAt) {
    Pass "Postgres recovery within budget: first 200 at ${RecoveredAt}s, stable through ${Budget}s"
    exit 0
}

Fail "App did not recover within ${Budget}s after Postgres restart"
