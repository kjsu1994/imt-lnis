[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$dist=Join-Path $PSScriptRoot 'serial-bridge'
$pidFile=Join-Path $dist 'bridge.pid'
$config=Join-Path $dist 'bridge.properties'
if (!(Test-Path $pidFile)) { return }
$bridgeProcessId=0
if (![int]::TryParse((Get-Content -Raw $pidFile).Trim(),[ref]$bridgeProcessId)) { throw 'Invalid bridge PID file.' }
$process=Get-CimInstance Win32_Process -Filter "ProcessId=$bridgeProcessId"
if (!$process) { return }
if (!$process.CommandLine.Contains('server.gnss.WindowsSerialBridge') -or !$process.CommandLine.Contains($config)) { throw 'PID does not belong to this COM bridge.' }
$props=@{}
foreach($line in Get-Content $config) { if($line.Contains('=')){ $pair=$line.Split('=',2);$props[$pair[0]]=$pair[1] } }
# Never terminate an active serial session through this helper.
$health=Invoke-RestMethod -Uri ("http://"+$props.bind+":"+$props.port+"/health") -Method Post -Headers @{Authorization=('Bearer '+$props.token)} -ContentType application/json -Body '{}' -TimeoutSec 3
if($health.busy) { throw 'GNSS is connected. Disconnect GNSS in the service before stopping the bridge.' }
Stop-Process -Id $bridgeProcessId -ErrorAction Stop
Write-Host 'Windows COM bridge stopped.'
