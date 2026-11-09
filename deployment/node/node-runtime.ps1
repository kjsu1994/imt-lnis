# Shared by installation and START/STOP; importing this file starts nothing.
function Read-NodeEnvironment([string]$File) {
    if (!(Test-Path -LiteralPath $File)) { throw "설정 파일이 없습니다: $File" }
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $File -Encoding UTF8) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$') {
            $values[$Matches[1]] = ($Matches[2] -replace '\s+#.*$', '').Trim().Trim('"', "'")
        }
    }
    return $values
}

function ConvertTo-NodeLinuxPath([string]$Path) {
    $full = [IO.Path]::GetFullPath($Path)
    if ($full -notmatch '^[A-Za-z]:\\' -or $full.Contains([char]10) -or $full.Contains([char]13)) {
        throw 'Windows 로컬 드라이브의 경로를 사용하세요.'
    }
    return '/mnt/' + $full.Substring(0, 1).ToLowerInvariant() + $full.Substring(2).Replace('\', '/')
}

function Get-NodeContext([string]$AppRoot) {
    $app = [IO.Path]::GetFullPath($AppRoot)
    $data = $app
    $installation = Join-Path $app 'installation.json'
    if (Test-Path -LiteralPath $installation) {
        $installed = Get-Content -Raw -LiteralPath $installation -Encoding UTF8 | ConvertFrom-Json
        $data = [IO.Path]::GetFullPath($installed.dataDirectory)
    }
    $envFile = Join-Path $data '.env'
    $settings = Read-NodeEnvironment $envFile
    if ($settings.LNIS_NODE_ROLE -notin @('sender','receiver')) { throw 'LNIS_NODE_ROLE은 sender 또는 receiver여야 합니다.' }
    $port = 8088
    if ($settings.LNIS_SERVER_PORT -and
        (![int]::TryParse($settings.LNIS_SERVER_PORT, [ref]$port) -or $port -lt 1 -or $port -gt 65535)) {
        throw 'LNIS_SERVER_PORT는 1~65535의 정수여야 합니다.'
    }
    $distribution = $settings.LNIS_WSL_DISTRIBUTION
    if (!$distribution) { $distribution = 'Ubuntu' }
    if ($distribution -notmatch '^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$') { throw 'WSL 배포판 이름을 확인하세요.' }
    return [pscustomobject]@{
        App=$app; Data=$data; EnvFile=$envFile; Settings=$settings; Port=$port
        Distribution=$distribution; Linux=(ConvertTo-NodeLinuxPath $app)
        Installed=(Test-Path -LiteralPath $installation)
    }
}

function Invoke-NodeWsl($Context, [string[]]$Arguments) {
    Get-Command wsl.exe -ErrorAction Stop | Out-Null
    $preference = $ErrorActionPreference
    $encoding = [Console]::OutputEncoding
    try {
        [Console]::OutputEncoding = New-Object Text.UTF8Encoding $false
        $ErrorActionPreference = 'Continue'
        $result = @(& wsl.exe -d $Context.Distribution --cd $Context.Linux --exec @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $preference
        [Console]::OutputEncoding = $encoding
    }
    if ($exitCode -ne 0) {
        $detail = ($result | Select-Object -Last 8 | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
        throw ("WSL 명령 실패 (종료 코드 " + $exitCode + "): " + $Arguments[0] + [Environment]::NewLine + $detail)
    }
    foreach ($entry in $result) {
        if ($entry -is [Management.Automation.ErrorRecord]) { Write-Warning $entry.ToString() }
        else { $entry }
    }
}

function Get-NodeCompose($Context) {
    $arguments = @('compose', '--env-file', (ConvertTo-NodeLinuxPath $Context.EnvFile), '-f', 'docker-compose.yml')
    if (Test-Path -LiteralPath (Join-Path $Context.App 'docker-compose.override.yml')) {
        $arguments += @('-f', 'docker-compose.override.yml')
    }
    if (Test-Path -LiteralPath (Join-Path $Context.Data '.serial-bridge.env')) {
        $arguments += @('-f', 'docker-compose.windows-serial.yml')
    }
    return $arguments
}

function Start-NodeKeepAlive($Context) {
    $record = Join-Path $Context.Data 'wsl-process.json'
    if (Test-Path -LiteralPath $record) {
        $saved = Get-Content -Raw -LiteralPath $record | ConvertFrom-Json
        $process = Get-CimInstance Win32_Process -Filter ("ProcessId=" + [int]$saved.pid)
        if ($process -and $process.Name -eq 'wsl.exe' -and $process.CommandLine -and
            $process.CommandLine.Contains($Context.Distribution) -and $process.CommandLine.Contains('sleep infinity') -and
            $process.CreationDate.ToUniversalTime().ToString('o') -eq $saved.createdAt) { return $false }
    }
    # Detach output handles so a calling CMD or deployment process can finish.
    $process = Start-Process -FilePath 'wsl.exe' -ArgumentList @('-d', $Context.Distribution, '--exec', 'sleep', 'infinity') -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $Context.Data 'wsl-start.log') -RedirectStandardError (Join-Path $Context.Data 'wsl-error.log')
    Start-Sleep -Milliseconds 300
    if ($process.HasExited) { throw 'WSL을 시작하지 못했습니다. 설치 상태를 확인하세요.' }
    $owner = Get-CimInstance Win32_Process -Filter ("ProcessId=" + $process.Id)
    if (!$owner) { throw 'WSL 유지 프로세스를 확인하지 못했습니다.' }
    @{pid=$process.Id; createdAt=$owner.CreationDate.ToUniversalTime().ToString('o')} |
        ConvertTo-Json | Set-Content -LiteralPath $record -Encoding UTF8
    return $true
}

function Stop-NodeKeepAlive($Context) {
    $record = Join-Path $Context.Data 'wsl-process.json'
    if (!(Test-Path -LiteralPath $record)) { return }
    $saved = Get-Content -Raw -LiteralPath $record | ConvertFrom-Json
    $process = Get-CimInstance Win32_Process -Filter ("ProcessId=" + [int]$saved.pid)
    if ($process -and $process.Name -eq 'wsl.exe' -and $process.CommandLine -and
        $process.CommandLine.Contains($Context.Distribution) -and $process.CommandLine.Contains('sleep infinity') -and
        $process.CreationDate.ToUniversalTime().ToString('o') -eq $saved.createdAt) {
        Stop-Process -Id $process.ProcessId -ErrorAction Stop
    }
    Remove-Item -LiteralPath $record
}

function Enter-NodeLock($Context) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $key = ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Context.App.ToLowerInvariant())))).Replace('-', '') }
    finally { $sha.Dispose() }
    $mutex = New-Object Threading.Mutex($false, ('Local\IMT-DTN-' + $key))
    try { $locked = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $locked = $true }
    if (!$locked) { $mutex.Dispose(); throw '다른 시작·종료·설치 작업이 진행 중입니다.' }
    return $mutex
}

function Get-NodeReady($Context) {
    foreach ($hostName in @('127.0.0.1', '[::1]')) {
        $url = 'http://' + $hostName + ':' + $Context.Port
        try {
            $node = Invoke-RestMethod "$url/lnis/api/v1/node" -TimeoutSec 2
            $health = Invoke-RestMethod "$url/lnis/api/v1/actuator/health/readiness" -TimeoutSec 2
            if ($node.online -and $node.role -ieq $Context.Settings.LNIS_NODE_ROLE -and $health.status -eq 'UP') {
                return $url
            }
        } catch {}
    }
    return $null
}

function Wait-NodeReady($Context) {
    $deadline = (Get-Date).AddMinutes(3)
    do {
        $url = Get-NodeReady $Context
        if ($url) { return $url }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "서비스 준비를 확인하지 못했습니다. Windows 내부 접속 포트 $($Context.Port)와 Docker 로그를 확인하세요."
}

function Assert-NodeIdle($Context, [string]$Url) {
    $node = Invoke-RestMethod "$Url/lnis/api/v1/node" -TimeoutSec 5
    $gnss = Invoke-RestMethod "$Url/lnis/api/v1/node/gnss" -TimeoutSec 5
    if ($node.state -ne 'READY' -or $gnss.capturing) { throw '수집·처리 중입니다. 작업 완료 후 설치를 다시 실행하세요.' }
    $page = 0
    do {
        $trials = @(Invoke-RestMethod "$Url/lnis/api/v1/dtn/tests?page=$page" -TimeoutSec 5)
        if ($trials | Where-Object { $_.state -in @('PREPARING','CALCULATING') -or $_.sendStatus -eq 'REQUESTING' }) {
            throw '시험 준비·송신·계산 중입니다. 작업 완료 후 설치를 다시 실행하세요.'
        }
        $page++
    } while ($trials.Count -eq 50)
}

function Assert-NodeFiles([string]$Root) {
    $manifest = Get-Content -LiteralPath (Join-Path $Root 'release.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $prefix = [IO.Path]::GetFullPath($Root).TrimEnd('\') + '\'
    if (!$manifest.files -or !$manifest.image -or !$manifest.imageId) { throw '배포 파일 목록이 올바르지 않습니다.' }
    foreach ($file in $manifest.files) {
        $target = [IO.Path]::GetFullPath((Join-Path $Root $file.path))
        $directory = Split-Path $target
        while ($directory.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
            if ((Get-Item -LiteralPath $directory).Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "배포 경로에 연결된 폴더가 있습니다: $($file.path)"
            }
            $directory = Split-Path $directory
        }
        if (!$target.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase) -or
            !(Test-Path -LiteralPath $target -PathType Leaf) -or
            ((Get-Item -LiteralPath $target).Attributes -band [IO.FileAttributes]::ReparsePoint) -or
            (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $file.sha256) {
            throw "배포 파일 무결성 확인 실패: $($file.path)"
        }
    }
    return $manifest
}

function Assert-NodePortMapping($Context) {
    $raw = Invoke-NodeWsl $Context (@('docker') + (Get-NodeCompose $Context) + @('config','--format','json'))
    $configuration = ($raw -join [Environment]::NewLine) | ConvertFrom-Json
    $serverPort = $configuration.services.node.environment.SERVER_PORT
    if (!$serverPort) { $serverPort = 8088 }
    if ($configuration.services.node.network_mode -eq 'host' -and [int]$serverPort -eq $Context.Port) {
        return
    }
    $ports = @($configuration.services.node.ports)
    $match = @($ports | Where-Object { [string]$_.published -eq [string]$Context.Port -and [int]$_.target -eq $Context.Port -and $_.protocol -eq 'tcp' })
    if (!$match.Count -or [int]$configuration.services.node.environment.SERVER_PORT -ne $Context.Port) {
        throw "포트 불일치: LNIS_SERVER_PORT=$($Context.Port), Windows 게시 포트·Docker 대상 포트·SERVER_PORT를 같게 설정하세요."
    }
}

function Assert-InstallLocation([string]$Path) {
    if ($Path -notmatch '^[A-Za-z]:\\' -or $Path -match '[\r\n]') {
        throw '설치 위치는 C:\compose와 같은 로컬 폴더로 지정하세요.'
    }
    $full = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    if ($full -eq [IO.Path]::GetPathRoot($full).TrimEnd('\')) {
        throw '드라이브 바로 아래에 폴더를 지정하세요. 예: C:\compose'
    }
    foreach ($protected in @($env:WINDIR, $env:ProgramFiles, ${env:ProgramFiles(x86)})) {
        if ($protected -and ($full -ieq $protected.TrimEnd('\') -or
                $full.StartsWith($protected.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase))) {
            throw 'Windows 또는 Program Files 폴더에는 설치할 수 없습니다. 예: C:\compose'
        }
    }
    $part = $full
    while ($part) {
        if (Test-Path -LiteralPath $part) {
            $item = Get-Item -LiteralPath $part
            if (!$item.PSIsContainer) { throw "폴더가 아닌 경로에는 설치할 수 없습니다: $part" }
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "연결된 폴더에는 설치할 수 없습니다: $part"
            }
        }
        $part = Split-Path $part
    }
    return $full
}

function Write-InstallEnvironment([string]$Example, [string]$Output, $Values) {
    $known = @{}
    $lines = foreach ($line in Get-Content -LiteralPath $Example -Encoding UTF8) {
        if ($line -match '^([A-Za-z_][A-Za-z0-9_]*)=') {
            $key = $Matches[1]
            $known[$key] = $true
            if ($Values.ContainsKey($key)) {
                $value = [string]$Values[$key]
                if ($value.Contains([char]10) -or $value.Contains([char]13) -or $value.Contains('#') -or $value.Contains('$')) {
                    throw "설정값에 줄바꿈 또는 #, $ 문자를 사용할 수 없습니다: $key"
                }
                $key + '=' + $value
            } else { $line }
        } else { $line }
    }
    foreach ($key in $Values.Keys | Sort-Object) {
        if (!$known.ContainsKey($key)) {
            if ($key -notmatch '^[A-Za-z_][A-Za-z0-9_]*$' -or [string]$Values[$key] -match '[\r\n#$]') {
                throw "추가 설정의 이름과 값을 확인하세요: $key"
            }
            $lines += @('# 기존 설치에서 유지한 추가 설정입니다.', ($key + '=' + $Values[$key]))
        }
    }
    [IO.File]::WriteAllLines($Output, $lines, (New-Object Text.UTF8Encoding $false))
}

function Assert-InstallSettings($Values) {
    if ($Values.LNIS_NODE_ROLE -notin @('sender','receiver')) { throw '역할은 sender 또는 receiver여야 합니다.' }
    $port = 0
    if (![int]::TryParse($Values.LNIS_SERVER_PORT,[ref]$port) -or $port -lt 1 -or $port -gt 65535) { throw '서비스 포트는 1~65535여야 합니다.' }
    foreach ($key in @('LNIS_NODE_BASE_URL','LNIS_NODE_PEER_URL','dtn_adapter')) {
        if ($key -ne 'LNIS_NODE_BASE_URL' -and [string]::IsNullOrWhiteSpace($Values[$key])) { continue }
        $uri = $null
        if (![Uri]::TryCreate($Values[$key],[UriKind]::Absolute,[ref]$uri) -or $uri.Scheme -ne 'http' -or $uri.UserInfo -or
            $uri.Query -or $uri.Fragment -or $uri.AbsolutePath -ne '/') { throw "IP와 포트까지의 http 주소를 입력하세요: $key" }
        if ($key -eq 'LNIS_NODE_BASE_URL' -and ($uri.HostNameType -ne 'IPv4' -or $uri.Port -ne $port)) {
            throw '현재 PC 주소에는 IPv4와 서비스 포트를 일치시켜 입력하세요.'
        }
    }
    if ($Values.LNIS_NODE_PEER_URL -and ([Uri]$Values.LNIS_NODE_PEER_URL).Equals([Uri]$Values.LNIS_NODE_BASE_URL)) {
        throw '상대 PC 주소는 내 PC 주소와 다르게 입력하세요.'
    }
    if ($Values.LNIS_NODE_MANAGEMENT_TOKEN.Length -lt 32) { throw '공통 관리 토큰은 32자 이상이어야 합니다.' }
    if ($Values.LNIS_NODE_ROLE -eq 'receiver' -and !$Values.LNIS_DTN_RECEIVE_TOKEN) { throw '수신 인증 토큰을 입력하세요.' }
}

function Get-NodeShortcutName([string]$Role) {
    if ($Role -notin @('sender','receiver')) { throw '송신·수신 역할을 확인하세요.' }
    return 'GNSS 전송 시험 · ' + $(if ($Role -eq 'sender') { '송신' } else { '수신' })
}
