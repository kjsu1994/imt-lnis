[CmdletBinding()]
param([switch]$NoBrowser)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'node-runtime.ps1')
$context = Get-NodeContext $PSScriptRoot
$lock = Enter-NodeLock $context
$created = $false
try {
    $created = Start-NodeKeepAlive $context
    Wait-NodeDocker $context
    $gnssReady = $true
    try {
        & (Join-Path $context.App 'start-serial-bridge.ps1') -Distribution $context.Distribution -DataDirectory $context.Data
    } catch {
        if ($_.Exception.Message -match '\[GNSS_(BUSY|CONFIG)\]') { throw }
        $gnssReady = $false
        Write-Warning "GNSS 연결 기능 사용 불가: $($_.Exception.Message)"
    }
    if ($context.Installed) {
        $release = Get-Content -Raw -LiteralPath (Join-Path $context.App 'release.json') -Encoding UTF8 | ConvertFrom-Json
        $imageId = (Invoke-NodeWsl $context @('docker','image','inspect','--format','{{.Id}}',$release.image)) -join ''
        if ($imageId.Trim() -ne $release.imageId) { throw '설치된 서비스 이미지가 배포본과 다릅니다. INSTALL.cmd를 다시 실행하세요.' }
    }
    $compose = Get-NodeCompose $context
    Assert-NodePortMapping $context
    # Compose reuses unchanged containers and applies edited env values without a rebuild.
    Invoke-NodeWsl $context (@('docker') + $compose + @('up','-d','--no-build','--pull','never')) | Out-Host
    $url = Wait-NodeReady $context
    $browserUrl = $context.Settings.LNIS_NODE_BASE_URL
    if (!$browserUrl) { $browserUrl = $url }
    Write-Host ("서비스 실행 완료: " + $context.Settings.LNIS_NODE_ROLE + " / $browserUrl")
    if (!$gnssReady) { Write-Warning '파일 기반 시험은 사용할 수 있습니다. GNSS 장비 수집은 중계 상태를 확인하세요.' }
    if ($context.Installed) {
        try {
            $public = Invoke-RestMethod ($context.Settings.LNIS_NODE_BASE_URL + '/lnis/api/v1/node') -TimeoutSec 3
            if (!$public.online -or $public.role -ine $context.Settings.LNIS_NODE_ROLE) { throw '잘못된 응답' }
        } catch { Write-Warning "PC 주소·포트 접속 실패: $($context.Settings.LNIS_NODE_BASE_URL). INSTALL.cmd의 네트워크 설정을 확인하세요." }
    }
    if (!$NoBrowser) { Start-Process $browserUrl }
} catch {
    if ($created) {
        try {
            $running = Invoke-NodeWsl $context (@('docker') + (Get-NodeCompose $context) + @('ps','--status','running','-q'))
            if (!$running) { Stop-NodeKeepAlive $context }
        } catch { Write-Warning 'WSL 유지 프로세스를 보존했습니다. STOP.cmd로 정리할 수 있습니다.' }
    }
    throw
} finally { $lock.ReleaseMutex(); $lock.Dispose() }
