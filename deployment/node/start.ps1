[CmdletBinding()]
param([switch]$NoBrowser)
$ErrorActionPreference = 'Stop'
$composeRoot = $PSScriptRoot
if ($composeRoot -notmatch '^[A-Za-z]:\\') { throw 'Windows 로컬 드라이브에서 실행하세요.' }
if (!(Test-Path -LiteralPath (Join-Path $composeRoot '.env'))) { throw '.env에 역할과 노드 주소를 설정하세요.' }
$linuxRoot = '/mnt/' + $composeRoot.Substring(0, 1).ToLowerInvariant() + $composeRoot.Substring(2).Replace('\', '/')
$portSetting = Get-Content -LiteralPath (Join-Path $composeRoot '.env') | Where-Object { $_ -match '^LNIS_SERVER_PORT=\d+$' } | Select-Object -Last 1
$nodePort = if ($portSetting) { [int]($portSetting.Split('=')[1]) } else { 8088 }
# Web/PVT remain in Docker. Both roles can optionally connect a local GNSS receiver.
$composeArgs = @('-f', 'docker-compose.yml')
if (Test-Path (Join-Path $composeRoot 'docker-compose.override.yml')) { $composeArgs += @('-f','docker-compose.override.yml') }
try {
    & (Join-Path $composeRoot 'start-serial-bridge.ps1')
    $composeArgs += @('-f','docker-compose.windows-serial.yml')
} catch {
    if ($_.Exception.Message -match '\[GNSS_(BUSY|CONFIG)\]') { throw }
    Write-Warning "GNSS bridge unavailable; starting file/REST service with system time. $($_.Exception.Message)"
}
& wsl.exe --cd $linuxRoot -- docker compose @composeArgs up -d --build
if ($LASTEXITCODE -ne 0) { throw '독립 노드 시작에 실패했습니다.' }
$deadline = (Get-Date).AddMinutes(3)
do {
    Start-Sleep -Seconds 3
    try {
        $status = Invoke-RestMethod "http://localhost:$nodePort/lnis/api/v1/node" -TimeoutSec 3
        if ($status.online) {
            Write-Host ('독립 노드 실행 완료: ' + $status.role + ' / ' + $status.agentId)
            if (!$NoBrowser) { Start-Process "http://localhost:$nodePort" }
            return
        }
    } catch { }
} while ((Get-Date) -lt $deadline)
& wsl.exe --cd $linuxRoot -- docker compose logs --tail 60 node
throw '로컬 실행기가 준비되지 않았습니다. SO 및 노드 로그를 확인하세요.'
