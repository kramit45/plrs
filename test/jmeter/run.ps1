# Run the JMeter recommendations-load scenario headless and produce an
# HTML report under test/jmeter/report/.
#
# Prerequisites: JMeter 5.6+ on PATH, PLRS app running, seed files loaded.
$ErrorActionPreference = 'Stop'
$dir   = Split-Path -Parent $MyInvocation.MyCommand.Path
$host_ = if ($env:HOST) { $env:HOST } else { 'localhost' }
$port  = if ($env:PORT) { $env:PORT } else { '8080' }
$threads  = if ($env:THREADS)  { $env:THREADS }  else { '20' }
$ramp     = if ($env:RAMP)     { $env:RAMP }     else { '600' }
$duration = if ($env:DURATION) { $env:DURATION } else { '1200' }

if (Test-Path "$dir\report")      { Remove-Item -Recurse -Force "$dir\report" }
if (Test-Path "$dir\results.jtl") { Remove-Item -Force "$dir\results.jtl" }

jmeter -n -t "$dir\recommendations-load.jmx" `
       -l "$dir\results.jtl" `
       -e -o "$dir\report" `
       "-Jhost=$host_" "-Jport=$port" `
       "-Jthreads=$threads" "-Jramp=$ramp" "-Jduration=$duration"

Write-Host ""
Write-Host "Report: $dir\report\index.html"
