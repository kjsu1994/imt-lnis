[CmdletBinding()]
param([switch]$NoBrowser)
$ErrorActionPreference = 'Stop'
$composeRoot = $PSScriptRoot
if ($composeRoot -notmatch '^[A-Za-z]:\\') { throw 'Windows 로컬 드라이브에서 실행하세요.' }
if (!(Test-Path -LiteralPath (Join-Path $composeRoot '.env'))) { throw '.env에 역할과 노드 주소를 설정하세요.' }
$linuxRoot = '/mnt/' + $composeRoot.Substring(0, 1).ToLowerInvariant() + $composeRoot.Substring(2).Replace('\', '/')
$portSetting = Get-Content -LiteralPath (Join-Path $composeRoot '.env') | Where-Object { $_ -match '^LNIS_SERVER_PORT=\d+$' } | Select-Object -Last 1
$nodePort = if ($portSetting) { [int]($portSetting.Split('=')[1]) } else { 8088 }
# 독립 노드는 컨테이너 하나의 JVM에서 실행한다. Windows Agent를 따로 시작하지 않는다.
& wsl.exe --cd $linuxRoot -- docker compose up -d --build
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
