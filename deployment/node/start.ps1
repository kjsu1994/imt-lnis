[CmdletBinding()]
param([switch]$NoBrowser, [string]$NetworkSetup, [switch]$SkipNetworkSetup)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'node-runtime.ps1')
if ($NetworkSetup) {
    Set-NodeNetwork (Get-Content -LiteralPath $NetworkSetup -Raw -Encoding UTF8 | ConvertFrom-Json)
    exit 0
}
$context = Get-NodeContext $PSScriptRoot
$lock = Enter-NodeLock $context
$created = $false
try {
    $created = Start-NodeKeepAlive $context
    Wait-NodeDocker $context
    if ($context.Installed) { Update-NodeAddress $context | Out-Null }
    $gnssReady = $true
    if ($context.Settings.LNIS_NODE_ROLE -eq 'sender') {
        try {
            & (Join-Path $context.App 'start-serial-bridge.ps1') -Distribution $context.Distribution -DataDirectory $context.Data
        } catch {
            if ($_.Exception.Message -match '\[GNSS_(BUSY|CONFIG)\]') { throw }
            $gnssReady = $false
            Write-Warning "GNSS 연결 기능 사용 불가: $($_.Exception.Message)"
        }
    } else {
        # 기존 Compose의 env_file 경로는 유지하되 수신부 중계 연결은 비운다.
        [IO.File]::WriteAllText((Join-Path $context.Data '.serial-bridge.env'),
            "LNIS_SERIAL_BRIDGE_URL=`nLNIS_SERIAL_BRIDGE_TOKEN=`n", [Text.UTF8Encoding]::new($false))
    }
    if ($context.Installed) {
        $release = Get-Content -Raw -LiteralPath (Join-Path $context.App 'release.json') -Encoding UTF8 | ConvertFrom-Json
        $imageId = (Invoke-NodeWsl $context @('docker','image','inspect','--format','{{.Id}}',$release.image)) -join ''
        if ($imageId.Trim() -ne $release.imageId -and (!$release.imageConfigId -or $imageId.Trim() -ne $release.imageConfigId)) { throw '설치된 서비스 이미지가 배포본과 다릅니다. INSTALL.cmd를 다시 실행하세요.' }
    }
    $jarHash = $null
    $jarChanged = $false
    if ($context.Installed -and $release.jarSha256) {
        $jar = Join-Path $context.App 'lnis.jar'
        if (!(Test-Path -LiteralPath $jar -PathType Leaf)) { throw '설치 폴더의 lnis.jar 파일을 확인하세요.' }
        $jarHash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash
        $jarChanged = $release.appliedJarSha256 -ne $jarHash
        if ($jarChanged) {
            Add-Type -AssemblyName System.IO.Compression.FileSystem
            $archive = [IO.Compression.ZipFile]::OpenRead($jar)
            try {
                if (!$archive.GetEntry('META-INF/MANIFEST.MF') -or
                    !$archive.GetEntry('BOOT-INF/classes/server/LnisApplication.class')) {
                    throw '서비스 실행용 lnis.jar 파일이 아닙니다.'
                }
            } finally { $archive.Dispose() }
            $runningUrl = Get-NodeReady $context
            if ($runningUrl) { Assert-NodeIdle $context $runningUrl }
            Write-Host '변경된 lnis.jar를 적용합니다.'
        }
    }
    $compose = Get-NodeCompose $context
    Assert-NodePortMapping $context
    # Compose reuses unchanged containers and applies edited env values without a rebuild.
    $up = @('up','-d','--no-build','--pull','never')
    if ($jarChanged) { $up += '--force-recreate' }
    Invoke-NodeWsl $context (@('docker') + $compose + $up) | Out-Host
    $url = Wait-NodeReady $context
    if ($jarChanged) {
        $release | Add-Member -NotePropertyName appliedJarSha256 -NotePropertyValue $jarHash -Force
        $release | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $context.App 'release.json') -Encoding UTF8
    }
    $browserUrl = $context.Settings.LNIS_NODE_BASE_URL
    if (!$browserUrl) { $browserUrl = $url }
    Write-Host ("서비스 실행 완료: " + $context.Settings.LNIS_NODE_ROLE + " / $browserUrl")
    if (!$gnssReady) { Write-Warning '파일 기반 시험은 사용할 수 있습니다. GNSS 장비 수집은 중계 상태를 확인하세요.' }
    if ($context.Installed -and !$SkipNetworkSetup) { Repair-NodeNetwork $context $url }
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
