[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$dist = Join-Path $PSScriptRoot 'serial-bridge'
$pidFile = Join-Path $dist 'bridge.pid'
$config = Join-Path $dist 'bridge.properties'
if (!(Test-Path -LiteralPath $pidFile)) { return }
$bridgeProcessId = 0
if (![int]::TryParse((Get-Content -Raw -LiteralPath $pidFile).Trim(), [ref]$bridgeProcessId)) {
    throw 'Invalid bridge PID file.'
}
$process = Get-CimInstance Win32_Process -Filter "ProcessId=$bridgeProcessId"
if (!$process -or !$process.CommandLine -or
    !$process.CommandLine.Contains('server.gnss.WindowsSerialBridge') -or !$process.CommandLine.Contains($config)) {
    # A Windows PID may have been reused. Remove only our stale record, never that process.
    Remove-Item -LiteralPath $pidFile
    Write-Host 'Removed stale COM bridge PID record; no process was stopped.'
    return
}
$props = @{}
foreach ($line in Get-Content -LiteralPath $config) {
    if ($line.Contains('=')) { $pair = $line.Split('=', 2); $props[$pair[0]] = $pair[1] }
}
# Failure to query health also leaves the process untouched.
$health = Invoke-RestMethod -Uri ("http://" + $props.bind + ":" + $props.port + "/health") -Method Post -Headers @{Authorization=('Bearer ' + $props.token)} -ContentType application/json -Body '{}' -TimeoutSec 3
if ($health.busy) { throw '[GNSS_BUSY] Disconnect GNSS in the service before stopping the bridge.' }
Stop-Process -Id $bridgeProcessId -ErrorAction Stop
Wait-Process -Id $bridgeProcessId -Timeout 5 -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $pidFile
Write-Host 'Windows COM bridge stopped.'
