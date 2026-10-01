[CmdletBinding()]
param([string]$JavaHome, [switch]$DockerDesktop)
$ErrorActionPreference = 'Stop'
function Get-BridgeSettings {
    param([string]$EnvironmentFile, [string]$ConfigFile)
    $settings = @{}
    foreach ($line in Get-Content -LiteralPath $EnvironmentFile) {
        if ($line -match '^\s*(LNIS_NODE_ROLE|LNIS_SERIAL_BRIDGE_(?:PORT|BIND|HOST))\s*=\s*(.*?)\s*$') {
            $key = $Matches[1]
            $settings[$key] = ($Matches[2] -replace '\s+#.*$', '').Trim().Trim('"', "'")
        }
    }
    $portText = $settings['LNIS_SERIAL_BRIDGE_PORT']
    if (!$portText -and (Test-Path -LiteralPath $ConfigFile)) {
        foreach ($line in Get-Content -LiteralPath $ConfigFile) {
            if ($line -match '^port=(.*)$') { $portText = $Matches[1].Trim() }
        }
    }
    if (!$portText) { $portText = if ($settings['LNIS_NODE_ROLE'] -ieq 'receiver') { '18766' } else { '18765' } }
    $port = 0
    if ($portText -notmatch '^[0-9]+$' -or ![int]::TryParse($portText, [ref]$port) -or $port -lt 1 -or $port -gt 65535) {
        throw '[GNSS_CONFIG] LNIS_SERIAL_BRIDGE_PORT must be an integer from 1 to 65535 in .env.'
    }
    $bind = $settings['LNIS_SERIAL_BRIDGE_BIND']
    $address = $null
    if ($bind -and (![Net.IPAddress]::TryParse($bind, [ref]$address) -or
        $address.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork -or $bind -eq '0.0.0.0')) {
        throw '[GNSS_CONFIG] LNIS_SERIAL_BRIDGE_BIND must be a local Windows IPv4 address.'
    }
    $containerHost = $settings['LNIS_SERIAL_BRIDGE_HOST']
    if ($containerHost -and [Uri]::CheckHostName($containerHost) -notin @([UriHostNameType]::Dns, [UriHostNameType]::IPv4)) {
        throw '[GNSS_CONFIG] LNIS_SERIAL_BRIDGE_HOST must be a hostname or IPv4 address without a scheme or port.'
    }
    return [pscustomobject]@{Port=$port; Bind=$bind; Host=$containerHost}
}

$root = $PSScriptRoot
$dist = Join-Path $root 'serial-bridge'
if (!(Test-Path (Join-Path $dist 'classes\server\gnss\WindowsSerialBridge.class'))) { throw 'Build and install windowsSerialBridgeDist first.' }
# Compare the complete installed byte bridge with the already running JVM.
$hashLines = @(Get-ChildItem -LiteralPath (Join-Path $dist 'classes'), (Join-Path $dist 'lib') -File -Recurse |
    Where-Object { $_.Extension -in @('.class', '.jar') } | Sort-Object FullName | ForEach-Object {
        $_.FullName.Substring($dist.Length + 1).Replace('\', '/') + '=' + (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
    })
$sha = [Security.Cryptography.SHA256]::Create()
try { $version = ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($hashLines -join "`n")))).Replace('-', '').ToLowerInvariant() }
finally { $sha.Dispose() }

$config = Join-Path $dist 'bridge.properties'
$settings = Get-BridgeSettings (Join-Path $root '.env') $config
$bridgePort = $settings.Port
$bindAddress = $settings.Bind
if (!$bindAddress) {
    $bindAddress = '127.0.0.1'
    if (!$DockerDesktop) {
        $route = (& wsl.exe -- sh -c 'ip -4 route show default') -join ' '
        if ($route -match 'default via ([0-9.]+)') { $bindAddress = $Matches[1] }
    }
}
if (!(Get-NetIPAddress -AddressFamily IPv4 | Where-Object IPAddress -eq $bindAddress)) {
    if ($settings.Bind) { throw '[GNSS_CONFIG] LNIS_SERIAL_BRIDGE_BIND is not assigned to this Windows PC.' }
    throw 'Cannot identify Windows address reachable from WSL.'
}
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
$containerHost = $settings.Host
if (!$containerHost) { $containerHost = if ($DockerDesktop) { 'host.docker.internal' } else { $bindAddress } }
$bridgeEnvironment = "LNIS_SERIAL_BRIDGE_URL=http://${containerHost}:${bridgePort}`nLNIS_SERIAL_BRIDGE_TOKEN=$token`n"
$headers = @{Authorization="Bearer $token"}
$health = $null
try {
    $health = Invoke-RestMethod -Uri "http://${bindAddress}:${bridgePort}/health" -Method Post -Headers $headers -ContentType application/json -Body '{}' -TimeoutSec 2
} catch {}
if ($health -and $health.status -eq 'UP' -and $health.version -eq $version) {
    [IO.File]::WriteAllText((Join-Path $root '.serial-bridge.env'), $bridgeEnvironment, $utf8)
    Write-Host "Windows COM bridge already running with the installed version (${bindAddress}:${bridgePort})."
    return
}
if ($health -and $health.busy) {
    throw '[GNSS_BUSY] Disconnect GNSS before updating the Windows COM bridge.'
}
$pidFile = Join-Path $dist 'bridge.pid'
if ($health -and !(Test-Path -LiteralPath $pidFile)) {
    throw 'An untracked bridge is running. Verify its process before updating it.'
}
# This helper verifies process ownership and refuses to stop an active session.
& (Join-Path $root 'stop-serial-bridge.ps1')
[IO.File]::WriteAllText((Join-Path $root '.serial-bridge.env'), $bridgeEnvironment, $utf8)
[IO.File]::WriteAllText((Join-Path $dist 'bridge-version.txt'), $version + "`n", $utf8)
[IO.File]::WriteAllText($config,"bind=$bindAddress`nport=$bridgePort`ntoken=$token`n",$utf8)
if (!$JavaHome) {
    $installed = Get-ChildItem (Join-Path $env:USERPROFILE '.jdks') -Directory -ErrorAction SilentlyContinue | Where-Object Name -Match '21' | Select-Object -First 1
    if ($installed) { $JavaHome=$installed.FullName }
}
$java = if ($JavaHome) { Join-Path $JavaHome 'bin\java.exe' } else { (Get-Command java.exe -ErrorAction Stop).Source }
$javaRelease = Get-Content -Raw (Join-Path (Split-Path (Split-Path $java)) 'release')
if ($javaRelease -notmatch 'JAVA_VERSION="(2[1-9]|[3-9][0-9])\.') { throw 'Java 21 or newer is required. Use -JavaHome.' }
$process = Start-Process -FilePath $java -ArgumentList @(('-Dlnis.bridge.version=' + $version), '-cp',('"'+$dist+'\classes;'+$dist+'\lib\*"'),'server.gnss.WindowsSerialBridge',('"'+$config+'"')) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $dist 'bridge.log') -RedirectStandardError (Join-Path $dist 'bridge-error.log')
[IO.File]::WriteAllText($pidFile,[string]$process.Id,$utf8)
for ($attempt=0;$attempt -lt 20;$attempt++) {
    Start-Sleep -Milliseconds 500
    try {
        $health=Invoke-RestMethod -Uri "http://${bindAddress}:${bridgePort}/health" -Method Post -Headers $headers -ContentType application/json -Body '{}' -TimeoutSec 2
        if ($health.status -eq 'UP' -and $health.version -eq $version) { Write-Host "Windows COM bridge ready (${bindAddress}:${bridgePort})."; return }
    } catch {}
    if ($process.HasExited) { throw "COM bridge exited. See $dist\bridge-error.log" }
}
throw "COM bridge did not start. See $dist\bridge-error.log"
