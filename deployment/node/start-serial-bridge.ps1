[CmdletBinding()]
param([string]$JavaHome, [switch]$DockerDesktop)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$dist = Join-Path $root 'serial-bridge'
if (!(Test-Path (Join-Path $dist 'classes\server\gnss\WindowsSerialBridge.class'))) { throw 'Build and install windowsSerialBridgeDist first.' }
$bindAddress = '127.0.0.1'
if (!$DockerDesktop) {
    $route = (& wsl.exe -- sh -c 'ip -4 route show default') -join ' '
    if ($route -match 'default via ([0-9.]+)') { $bindAddress = $Matches[1] }
}
if (!(Get-NetIPAddress -AddressFamily IPv4 | Where-Object IPAddress -eq $bindAddress)) { throw 'Cannot identify Windows address reachable from WSL.' }
$config = Join-Path $dist 'bridge.properties'
$roleLine = Get-Content (Join-Path $root '.env') | Where-Object { $_ -match '^LNIS_NODE_ROLE=' } | Select-Object -Last 1
$bridgePort = if ($roleLine -and $roleLine.Split('=',2)[1].Trim().Trim('"',"'") -ieq 'receiver') { 18766 } else { 18765 }
$token = $null
if (Test-Path $config) {
    foreach ($line in Get-Content $config) { if ($line.StartsWith('token=')) { $token=$line.Substring(6) } }
}
if (!$token) {
    $random = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($random) } finally { $rng.Dispose() }
    $token = [Convert]::ToBase64String($random)
}
$utf8 = New-Object Text.UTF8Encoding $false
$containerHost = if ($DockerDesktop) { 'host.docker.internal' } else { $bindAddress }
[IO.File]::WriteAllText((Join-Path $root '.serial-bridge.env'), "LNIS_SERIAL_BRIDGE_URL=http://${containerHost}:${bridgePort}`nLNIS_SERIAL_BRIDGE_TOKEN=$token`n", $utf8)
$headers = @{Authorization="Bearer $token"}
try {
    $health=Invoke-RestMethod -Uri "http://${bindAddress}:${bridgePort}/health" -Method Post -Headers $headers -ContentType application/json -Body '{}' -TimeoutSec 2
    if ($health.status -eq 'UP') { Write-Host "Windows COM bridge already running ($bindAddress)."; return }
} catch {}
$pidFile=Join-Path $dist 'bridge.pid'
if (Test-Path $pidFile) {
    $oldBridgeId=0
    if ([int]::TryParse((Get-Content -Raw $pidFile).Trim(),[ref]$oldBridgeId)) {
        $old=Get-CimInstance Win32_Process -Filter "ProcessId=$oldBridgeId" -ErrorAction SilentlyContinue
        if ($old -and $old.CommandLine.Contains('server.gnss.WindowsSerialBridge') -and $old.CommandLine.Contains($config)) {
            Stop-Process -Id $oldBridgeId -ErrorAction Stop
        }
    }
}
[IO.File]::WriteAllText($config,"bind=$bindAddress`nport=$bridgePort`ntoken=$token`n",$utf8)
if (!$JavaHome) {
    $installed = Get-ChildItem (Join-Path $env:USERPROFILE '.jdks') -Directory -ErrorAction SilentlyContinue | Where-Object Name -Match '21' | Select-Object -First 1
    if ($installed) { $JavaHome=$installed.FullName }
}
$java = if ($JavaHome) { Join-Path $JavaHome 'bin\java.exe' } else { (Get-Command java.exe -ErrorAction Stop).Source }
$version = Get-Content -Raw (Join-Path (Split-Path (Split-Path $java)) 'release')
if ($version -notmatch 'JAVA_VERSION="(2[1-9]|[3-9][0-9])\.') { throw 'Java 21 or newer is required. Use -JavaHome.' }
$process = Start-Process -FilePath $java -ArgumentList @('-cp',('"'+$dist+'\classes;'+$dist+'\lib\*"'),'server.gnss.WindowsSerialBridge',('"'+$config+'"')) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $dist 'bridge.log') -RedirectStandardError (Join-Path $dist 'bridge-error.log')
[IO.File]::WriteAllText($pidFile,[string]$process.Id,$utf8)
for ($attempt=0;$attempt -lt 20;$attempt++) {
    Start-Sleep -Milliseconds 500
    try {
        $health=Invoke-RestMethod -Uri "http://${bindAddress}:${bridgePort}/health" -Method Post -Headers $headers -ContentType application/json -Body '{}' -TimeoutSec 2
        if ($health.status -eq 'UP') { Write-Host "Windows COM bridge ready ($bindAddress)."; return }
    } catch {}
    if ($process.HasExited) { throw "COM bridge exited. See $dist\bridge-error.log" }
}
throw "COM bridge did not start. See $dist\bridge-error.log"
