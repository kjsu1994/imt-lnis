[CmdletBinding()]
param(
    [string]$Destination = (Join-Path $env:SystemDrive 'compose'),
    [string]$BundleRoot,
    [string]$Configuration,
    [switch]$SystemSetup,
    [string]$NetworkSetup,
    [switch]$ReplaceStaleProxy,
    [switch]$NoBrowser,
    [string]$LogFile
)
$ErrorActionPreference = 'Stop'

$script:installStage = '설치 파일 확인'
trap {
    Write-InstallFailure $_
    exit 1
}

function Set-InstallStage([string]$Message) {
    $script:installStage = $Message
    Write-Host $Message
}

function Write-InstallFailure($Failure) {
    $message = $script:installStage + [Environment]::NewLine + $Failure.Exception.Message
    if ($LogFile) {
        try {
            [IO.Directory]::CreateDirectory((Split-Path $LogFile)) | Out-Null
            [IO.File]::WriteAllText($LogFile + '.error.txt', $message, (New-Object Text.UnicodeEncoding $false,$false))
        } catch { Write-Warning '설치 오류 요약을 저장하지 못했습니다.' }
    }
    Write-Host ('설치 중단: ' + $message)
    Write-Host $Failure.ScriptStackTrace
}


function Invoke-InstallerAdmin([string[]]$Arguments) {
    $args = @('-NoProfile','-ExecutionPolicy','Bypass','-File',('"' + $PSCommandPath + '"'),'-BundleRoot',('"' + $script:bundle + '"')) + $Arguments
    if ($LogFile) { $args += @('-LogFile', ('"' + $LogFile + '.admin.log"')) }
    $child = Start-Process powershell.exe -ArgumentList $args -Verb RunAs -WindowStyle Hidden -Wait -PassThru
    if ($child.ExitCode -eq 3010) {
        Write-Host 'Windows 구성요소를 준비했습니다. PC를 재부팅한 뒤 설치 프로그램을 다시 실행하세요.'
        exit 3010
    }
    if ($child.ExitCode -ne 0) {
        $detail = if ($LogFile -and (Test-Path -LiteralPath ($LogFile + '.admin.log.error.txt'))) {
            Get-Content -LiteralPath ($LogFile + '.admin.log.error.txt') -Raw -Encoding Unicode
        } else { '관리자 승인과 설치 기록을 확인하세요.' }
        throw "관리자 설정 실패 (종료 코드 $($child.ExitCode)): $detail"
    }
}

function Get-InstalledWslVersion {
    try {
        $text = (& wsl.exe --version 2>$null) -join ' '
        if ($LASTEXITCODE -eq 0 -and ($text -replace [char]0, '') -match '(\d+\.\d+\.\d+(?:\.\d+)?)') {
            return [version]$Matches[1]
        }
    } catch {}
    return $null
}

function Test-NodeWslReady {
    $version = Get-InstalledWslVersion
    $build = (Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion').CurrentBuildNumber
    if ([int]$build -ge 22000 -and $env:PROCESSOR_ARCHITECTURE -eq 'AMD64' -and $version -and $version -ge [version]'0.67.6') {
        $ready = Get-NodeWslRuntimes | Where-Object { $_.Version -eq 2 -and $_.Name -notmatch '^LNIS-Runtime(?:-|$)' } | Select-Object -First 1
        if ($ready) {
            $preference = $ErrorActionPreference
            try {
                $ErrorActionPreference = 'Continue'
                & wsl.exe -d $ready.Name --cd / --exec /bin/true 2>$null
                $working = $LASTEXITCODE -eq 0
            } finally { $ErrorActionPreference = $preference }
            if ($working) {
                Write-Host ("기존 WSL2 실행 확인: " + $ready.Name)
                return $true
            }
        }
    }
    return $false
}

function Install-WslPrerequisites([string]$Offline) {
    if (Test-NodeWslReady) { return $false }
    $os = Get-CimInstance Win32_OperatingSystem
    if ([int]$os.BuildNumber -lt 22000 -or $env:PROCESSOR_ARCHITECTURE -ne 'AMD64') { throw 'Windows 11 x64가 필요합니다.' }
    $processor = Get-CimInstance Win32_Processor | Select-Object -First 1
    if (!$processor.VirtualizationFirmwareEnabled -and !(Get-CimInstance Win32_ComputerSystem).HypervisorPresent) { throw 'BIOS/UEFI의 가상화 기능을 활성화하세요.' }
    $restart = $false
    foreach ($name in @('Microsoft-Windows-Subsystem-Linux','VirtualMachinePlatform')) {
        if ((Get-WindowsOptionalFeature -Online -FeatureName $name).State -ne 'Enabled') {
            Write-Host ("Windows 기능 준비: " + $name)
            $result = Enable-WindowsOptionalFeature -Online -FeatureName $name -All -NoRestart -LimitAccess
            $restart = $restart -or $result.RestartNeeded
        }
    }
    $installedVersion = Get-InstalledWslVersion
    # systemd requires WSL 0.67.6; a newer bundled MSI alone does not require a global update.
    if ($installedVersion -and $installedVersion -ge [version]'0.67.6') {
        Write-Host ("기존 WSL 재사용: " + $installedVersion)
        return $restart
    }
    $msi = Join-Path $Offline 'wsl.msi'
    Write-Host '오프라인 WSL 설치 중'
    $msiArguments = @('/i',('"' + $msi + '"'),'/qn','/norestart')
    if ($LogFile) { $msiArguments += @('/l*v',('"' + $LogFile + '.wsl-msi.log"')) }
    $process = Start-Process msiexec.exe -ArgumentList $msiArguments -Wait -WindowStyle Hidden -PassThru
    if ($process.ExitCode -notin @(0,1638,3010)) { throw "오프라인 WSL 설치 실패: $($process.ExitCode)" }
    return ($restart -or $process.ExitCode -eq 3010)
}

function Get-NodeWslRuntimes {
    $default = (Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Lxss' -ErrorAction SilentlyContinue).DefaultDistribution
    foreach ($entry in Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Lxss\*' -ErrorAction SilentlyContinue) {
        if ($entry.DistributionName -and $entry.BasePath) {
            $location = [Environment]::ExpandEnvironmentVariables($entry.BasePath)
            if ($location.StartsWith('\\?\')) { $location = $location.Substring(4) }
            [pscustomobject]@{Name=$entry.DistributionName; Location=[IO.Path]::GetFullPath($location).TrimEnd('\'); Version=$entry.Version; IsDefault=($entry.PSChildName -eq $default)}
        }
    }
}

function Initialize-NodeRuntime([string]$Destination, [string]$Data, [string]$Offline, [string]$PreviousDistribution) {
    $runtimes = @(Get-NodeWslRuntimes | Where-Object { $_.Version -eq 2 -and $_.Name -notmatch '^LNIS-Runtime(?:-|$)' })
    $runtime = $runtimes | Where-Object Name -eq $PreviousDistribution | Select-Object -First 1
    if (!$runtime) { $runtime = $runtimes | Where-Object IsDefault | Select-Object -First 1 }
    if (!$runtime -and $runtimes.Count -eq 1) { $runtime = $runtimes[0] }
    if (!$runtime -and $runtimes.Count) {
        throw 'WSL 배포판이 여러 개입니다. 사용할 Linux를 wsl --set-default 배포판명 으로 지정한 뒤 다시 설치하세요.'
    }
    if (!$runtime) {
        if (@(Get-NodeWslRuntimes | Where-Object Name -eq 'Ubuntu').Count) { throw '기존 Ubuntu를 WSL2로 전환한 뒤 다시 설치하세요.' }
        $location = Join-Path $Destination 'wsl'
        $index = 0
        while (Test-Path -LiteralPath $location) {
            $index++
            $location = Join-Path $Destination ('wsl-' + $index)
        }
        Write-Host '기존 Linux가 없어 오프라인 Ubuntu를 준비합니다.'
        & wsl.exe --import Ubuntu $location (Join-Path $Offline 'ubuntu-runtime.tar') --version 2 | Out-Host
        if ($LASTEXITCODE -ne 0) { throw 'Ubuntu 준비에 실패했습니다. 가상화·재부팅 여부와 설치 기록을 확인하세요.' }
        $runtime = [pscustomobject]@{Name='Ubuntu';Location=$location}
    }
    @{distribution=$runtime.Name;location=$runtime.Location;mode='shared';owner=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value} |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $Data 'runtime-registration.json') -Encoding UTF8
    Write-Host ("Linux Docker 엔진 사용: " + $runtime.Name)
    return $runtime.Name
}

function Read-InstallSecret([string]$Prompt) {
    $value = Read-Host $Prompt -AsSecureString
    $handle = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($value)
    try {
        $text = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($handle)
        if (!$text) { return 'imt123' }
        return $text
    }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($handle) }
}


function Get-InstallSettings([string]$Existing, [string]$Provided) {
    if ($Provided) { return Read-NodeEnvironment $Provided }
    if (Test-Path -LiteralPath $Existing) { return Read-NodeEnvironment $Existing }
    Write-Host '설치 담당자용 설정입니다. 양쪽 PC의 관리 토큰은 같아야 합니다.'
    $role = Read-Host '역할 (sender / receiver)'
    $port = Read-Host '서비스 포트 (기본 8088)'
    if (!$port) { $port = '8088' }
    $settings = @{
        LNIS_NODE_ROLE=$role; LNIS_SERVER_PORT=$port
        LNIS_NODE_BASE_URL=('http://' + (Read-Host '현재 PC IPv4 주소') + ':' + $port)
        LNIS_NODE_PEER_URL=(Read-Host '상대 서비스 주소 (예: http://192.168.1.73:8088)')
        dtn_adapter=(Read-Host '현재 PC와 연결할 어댑터 주소 (예: http://192.168.1.154:8080)')
        LNIS_NODE_MANAGEMENT_TOKEN=(Read-InstallSecret '양쪽 공통 관리 인증값 (기본 imt123)')
        LNIS_DTN_SEND_TOKEN=(Read-InstallSecret '송신 어댑터 인증값 (기본 imt123)')
        LNIS_DTN_RECEIVE_TOKEN='imt123'
    }
    if ($role -eq 'receiver') { $settings.LNIS_DTN_RECEIVE_TOKEN = Read-InstallSecret '수신 어댑터 인증값 (기본 imt123)' }
    return $settings
}


if ($LogFile) {
    New-Item -ItemType Directory -Path (Split-Path $LogFile) -Force | Out-Null
    if (Test-Path -LiteralPath ($LogFile + '.error.txt')) { Remove-Item -LiteralPath ($LogFile + '.error.txt') }
    Start-Transcript -Path $LogFile -Append -Force | Out-Null
}
if (!$BundleRoot) { $BundleRoot = Split-Path $PSScriptRoot }
$script:bundle = [IO.Path]::GetFullPath($BundleRoot)
. (Join-Path $script:bundle 'app\node-runtime.ps1')
$release = Assert-NodeFiles $script:bundle
if ($SystemSetup) {
    try {
        if (!$LogFile) { Start-Transcript -Path (Join-Path $script:bundle 'installer\install.log') -Append -Force | Out-Null }
        Set-InstallStage '[2/7] Windows 및 WSL 구성요소 확인'
        if (Install-WslPrerequisites (Join-Path $script:bundle 'offline')) { exit 3010 }
        exit 0
    } catch { throw }
}
if ($NetworkSetup) {
    try {
        if (!$LogFile) { Start-Transcript -Path (Join-Path $script:bundle 'installer\install.log') -Append -Force | Out-Null }
        Set-InstallStage '[6/7] Windows·WSL 접속 포트 적용'
        Set-NodeNetwork (Get-Content -Raw -LiteralPath $NetworkSetup -Encoding UTF8 | ConvertFrom-Json) -ReplaceStaleProxy:$ReplaceStaleProxy
        exit 0
    } catch { throw }
}
Set-InstallStage '[1/7] 설치 경로와 설정 확인'
$destinationRoot = Assert-InstallLocation $Destination
$layout = Get-NodeInstallLayout $destinationRoot
$app = $destinationRoot
$data = $destinationRoot
$existing = Join-Path $data '.env'
$values = Get-InstallSettings $layout.EnvFile $Configuration
Assert-InstallSettings $values
$values.LNIS_NODE_IMAGE = $release.image
$values.LNIS_STATE_DIR = ConvertTo-NodeLinuxPath $data
if (!$values.ContainsKey('LNIS_IQ_ENABLED')) { $values.LNIS_IQ_ENABLED = [string]($values.LNIS_NODE_ROLE -eq 'sender') }
if (!$values.LNIS_IQ_HOST_PATH -or $values.LNIS_IQ_HOST_PATH -ieq (Join-Path $layout.Data 'exchange')) {
    $values.LNIS_IQ_HOST_PATH = Join-Path $data 'exchange'
}
if (!$layout.Existing -and !$values.LNIS_SERIAL_BRIDGE_PORT) {
    $usedPorts = [Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners().Port
    $bridgePort = if ($values.LNIS_NODE_ROLE -eq 'receiver') { 18766 } else { 18765 }
    while ($usedPorts -contains $bridgePort -or $bridgePort -eq [int]$values.LNIS_SERVER_PORT) { $bridgePort++ }
    if ($bridgePort -gt 65535) { throw 'COM 중계에 사용할 빈 포트를 찾지 못했습니다.' }
    $values.LNIS_SERIAL_BRIDGE_PORT = [string]$bridgePort
}

$dbBytes = 0
if (Test-Path -LiteralPath (Join-Path $layout.Data 'DB')) {
    $dbBytes = (Get-ChildItem -LiteralPath (Join-Path $layout.Data 'DB') -File -Recurse -Force | Measure-Object Length -Sum).Sum
    if (!$layout.Existing -and $dbBytes) { throw '기존 DB의 .env 설정을 확인한 뒤 다시 설치하세요. DB는 변경하지 않았습니다.' }
}
$drive = New-Object IO.DriveInfo ([IO.Path]::GetPathRoot($destinationRoot))
$tempDrive = New-Object IO.DriveInfo ([IO.Path]::GetPathRoot([IO.Path]::GetTempPath()))
if ($drive.AvailableFreeSpace -lt (12GB + $dbBytes) -or $tempDrive.AvailableFreeSpace -lt (2GB + $dbBytes)) {
    throw '설치와 복구 작업에 필요한 디스크 여유 공간이 부족합니다.'
}
Set-InstallStage '[2/7] Windows 및 WSL 구성요소 확인'
if (!(Test-NodeWslReady)) { Invoke-InstallerAdmin @('-SystemSetup') }
New-Item -ItemType Directory -Path $destinationRoot -Force | Out-Null
$work = Join-Path ([IO.Path]::GetTempPath()) ('gnss-install-' + [Guid]::NewGuid().ToString('N'))
$stage = Join-Path $work 'payload'
$restore = Join-Path $work 'restore'
New-Item -ItemType Directory -Path $work,$restore -Force | Out-Null
$oldContext = $null
$context = $null
$lock = $null
$payloadNames = @()
$movedData = New-Object 'Collections.Generic.List[object]'
$deployed = $false
$stopped = $false
$keepRecovery = $false
try {
    Copy-Item -LiteralPath (Join-Path $script:bundle 'app') -Destination $stage -Recurse
    Assert-NodeFiles $stage | Out-Null
    # A retained WSL disk named runtime must not be replaced by the bundled Java runtime.
    if ((Test-Path -LiteralPath (Join-Path $stage 'runtime')) -and
            (Test-Path -LiteralPath (Join-Path $app 'runtime')) -and
            !(Test-Path -LiteralPath (Join-Path $app 'runtime\bin\java.exe'))) {
        Rename-Item -LiteralPath (Join-Path $stage 'runtime') -NewName 'jre'
        $manifestFile = Join-Path $stage 'release.json'
        $manifest = Get-Content -LiteralPath $manifestFile -Raw -Encoding UTF8 | ConvertFrom-Json
        foreach ($file in $manifest.files) { $file.path = $file.path -replace '^runtime/', 'jre/' }
        $manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $manifestFile -Encoding UTF8
    }
    $payloadNames = @((Get-ChildItem -LiteralPath $stage -Force).Name) + @('installation.json')
    foreach ($name in $payloadNames) {
        if ($name -in @('DB','exchange','data','app','backups','.env','.serial-bridge.env','network.json','runtime-registration.json')) {
            throw ("프로그램 배포 목록에 데이터 경로가 포함되어 있습니다: " + $name)
        }
        $target = Join-Path $app $name
        if ((Test-Path -LiteralPath $target) -and (Get-Item -LiteralPath $target -Force).Attributes.HasFlag([IO.FileAttributes]::ReparsePoint)) {
            throw ("연결된 프로그램 폴더는 교체하지 않습니다: " + $target)
        }
    }
    if ($layout.Existing) {
        Assert-InstallLocation $layout.App | Out-Null
        Assert-InstallLocation $layout.Data | Out-Null
        foreach ($name in @('DB','serial-bridge')) {
            $target = Join-Path $layout.Data $name
            if ((Test-Path -LiteralPath $target) -and (Get-Item -LiteralPath $target -Force).Attributes.HasFlag([IO.FileAttributes]::ReparsePoint)) {
                throw ("연결된 데이터 폴더를 보존했습니다. 실제 경로를 확인하세요: " + $target)
            }
        }
    }

    $lock = Enter-NodeLock ([pscustomobject]@{App=$app})
    if ($layout.Existing) {
        $oldContext = Get-NodeContext $layout.App
        if ($oldContext.Data -ine $layout.Data) { throw '기존 설치의 데이터 경로가 다릅니다. 원래 설치 위치를 확인하세요.' }
        if ($oldContext.Settings.LNIS_NODE_ROLE -ine $values.LNIS_NODE_ROLE) { throw '기존 DB의 송신·수신 역할은 변경할 수 없습니다.' }
        $url = Get-NodeReady $oldContext
        if ($url) {
            Assert-NodeIdle $oldContext $url
            & (Join-Path $layout.App 'stop.ps1')
        } else {
            if (@(Get-NodeWslRuntimes | Where-Object Name -eq $oldContext.Distribution).Count) {
                Start-NodeKeepAlive $oldContext | Out-Null
                Wait-NodeDocker $oldContext
                $filter = if ($oldContext.Settings.LNIS_COMPOSE_PROJECT) { 'label=com.docker.compose.project=' + $oldContext.Settings.LNIS_COMPOSE_PROJECT } else { 'label=com.docker.compose.project.working_dir=' + $oldContext.Linux }
                if (Invoke-NodeWsl $oldContext @('docker','ps','--quiet','--filter',$filter)) {
                    throw '기존 서비스의 상태를 확인할 수 없습니다. 시험 상태와 로그를 확인한 뒤 다시 설치하세요.'
                }
            }
            & (Join-Path $layout.App 'stop-serial-bridge.ps1') -DataDirectory $oldContext.Data
            Stop-NodeKeepAlive $oldContext
        }
        $stopped = $true
        $dataRestore = Join-Path $restore 'data'
        New-Item -ItemType Directory -Path $dataRestore -Force | Out-Null
        foreach ($name in @('DB','.env','.serial-bridge.env','serial-bridge','network.json','runtime-registration.json')) {
            $source = Join-Path $layout.Data $name
            if (Test-Path -LiteralPath $source) { Copy-Item -LiteralPath $source -Destination $dataRestore -Recurse -Force }
        }
    }
    $codeRestore = Join-Path $restore 'code'
    New-Item -ItemType Directory -Path $codeRestore -Force | Out-Null
    foreach ($name in $payloadNames) {
        $source = Join-Path $app $name
        if (Test-Path -LiteralPath $source) { Copy-Item -LiteralPath $source -Destination $codeRestore -Recurse -Force }
    }
    if ($layout.Data -ine $data) {
        foreach ($item in Get-ChildItem -LiteralPath $layout.Data -Force) {
            $target = Join-Path $data $item.Name
            if (Test-Path -LiteralPath $target) { throw ("기존 데이터와 같은 이름의 항목이 있습니다. 원본을 보존했습니다: " + $target) }
        }
        foreach ($item in Get-ChildItem -LiteralPath $layout.Data -Force) {
            $target = Join-Path $data $item.Name
            Move-Item -LiteralPath $item.FullName -Destination $target
            $movedData.Add(@{Source=$item.FullName;Target=$target})
        }
    }
    New-Item -ItemType Directory -Path (Join-Path $data 'DB'),(Join-Path $data 'exchange') -Force | Out-Null
    Set-InstallStage '[3/7] WSL Docker 실행 환경 확인'
    $previousDistribution = if ($oldContext) { $oldContext.Distribution } else { $values.LNIS_WSL_DISTRIBUTION }
    $values.LNIS_WSL_DISTRIBUTION = Initialize-NodeRuntime $destinationRoot $data (Join-Path $script:bundle 'offline') $previousDistribution
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $key = ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($app.ToLowerInvariant())))).Replace('-','').Substring(0,12).ToLowerInvariant() }
    finally { $sha.Dispose() }
    $values.LNIS_COMPOSE_PROJECT = 'gnss-' + $key
    $deployed = $true
    foreach ($name in $payloadNames) {
        $source = Join-Path $stage $name
        if (!(Test-Path -LiteralPath $source)) { continue }
        $target = Join-Path $app $name
        if ((Test-Path -LiteralPath $target -PathType Container) -and (Test-Path -LiteralPath $source -PathType Container)) {
            foreach ($item in Get-ChildItem -LiteralPath $source -Force) {
                Copy-Item -LiteralPath $item.FullName -Destination (Join-Path $target $item.Name) -Recurse -Force
            }
        } else {
            Copy-Item -LiteralPath $source -Destination $target -Recurse -Force
        }
    }
    @{dataDirectory=$data} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $app 'installation.json') -Encoding UTF8
    Write-InstallEnvironment (Join-Path $app '.env.example') $existing $values
    $context = Get-NodeContext $app
    Set-InstallStage '[4/7] Docker 및 서비스 이미지 준비'
    Start-NodeKeepAlive $context | Out-Null
    Wait-NodeDocker $context
    Invoke-NodeWsl $context @('docker','load','--input',(ConvertTo-NodeLinuxPath (Join-Path $script:bundle 'offline\service-image.tar'))) | Out-Host
    Set-InstallStage '[5/7] 서비스 실행 및 준비 상태 확인'
    & (Join-Path $app 'start.ps1') -NoBrowser -SkipNetworkSetup
    $context = Get-NodeContext $app
    $values.LNIS_NODE_BASE_URL = $context.Settings.LNIS_NODE_BASE_URL
    $localUrl = Wait-NodeReady $context
    $javaDirectory = if (Test-Path -LiteralPath (Join-Path $app 'jre\bin\java.exe')) { 'jre' } else { 'runtime' }
    $network = @{
        listenAddress='0.0.0.0'; port=$context.Port
        loopback=([Uri]$localUrl).Host.Trim('[',']'); record=(Join-Path $data 'network.json')
        bridgePort=(Get-NodeBridgePort $context)
        java=(Join-Path $app ($javaDirectory + '\bin\java.exe'))
    }
    $networkFile = Join-Path $work 'network-install.json'
    $network | ConvertTo-Json | Set-Content -LiteralPath $networkFile -Encoding UTF8
    Set-InstallStage '[6/7] Windows·WSL 접속 포트 적용'
    Invoke-InstallerAdmin @('-NetworkSetup',('"' + $networkFile + '"'),'-ReplaceStaleProxy')
    $public = Invoke-RestMethod ($values.LNIS_NODE_BASE_URL + '/lnis/api/v1/node') -TimeoutSec 5
    if (!$public.online -or $public.role -ine $values.LNIS_NODE_ROLE) { throw 'Windows PC 주소와 WSL 서비스 연결을 확인하지 못했습니다.' }
    Set-InstallStage '[7/7] 바탕화면 바로가기 생성'
    $shell = New-Object -ComObject WScript.Shell
    foreach ($name in @('START','STOP')) {
        $label = if ($name -eq 'START') { Get-NodeShortcutName $values.LNIS_NODE_ROLE } else { 'DTN STOP' }
        $link = $shell.CreateShortcut((Join-Path ([Environment]::GetFolderPath('Desktop')) ($label + '.lnk')))
        $link.TargetPath = Join-Path $app ($name + '.cmd')
        $link.WorkingDirectory = $app
        if (Test-Path -LiteralPath (Join-Path $app 'program.ico')) { $link.IconLocation = Join-Path $app 'program.ico' }
        $link.Save()
    }
    if ($layout.App -ine $app) {
        # Remove only files listed by the old release; leave unrecognised user files alone.
        $oldManifest = Join-Path $layout.App 'release.json'
        if (Test-Path -LiteralPath $oldManifest) {
            $oldRelease = Get-Content -LiteralPath $oldManifest -Raw -Encoding UTF8 | ConvertFrom-Json
            $known = @($oldRelease.files.path) + @('release.json','installation.json')
            $unknown = @(Get-ChildItem -LiteralPath $layout.App -File -Recurse -Force | Where-Object {
                $_.FullName.Substring($layout.App.Length+1).Replace('\','/') -notin $known
            })
            if (!$unknown.Count) { Remove-Item -LiteralPath $layout.App -Recurse -Force }
        }
        if (!(Get-ChildItem -LiteralPath $layout.Data -Force | Select-Object -First 1)) { Remove-Item -LiteralPath $layout.Data -Force }
    }
    Write-Host '설치 완료. 설치 폴더의 START.cmd 또는 바탕화면 바로가기로 실행하세요.'
    if (!$NoBrowser) { Start-Process $values.LNIS_NODE_BASE_URL }
} catch {
    $failure = $_
    try {
        if ($context -and $deployed) { & (Join-Path $app 'stop.ps1') }
        if ($oldContext -and $stopped) {
            if ($deployed) {
                foreach ($name in $payloadNames) {
                    $target = Join-Path $app $name
                    if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
                }
                foreach ($item in Get-ChildItem -LiteralPath $codeRestore -Force) {
                    Copy-Item -LiteralPath $item.FullName -Destination (Join-Path $app $item.Name) -Recurse -Force
                }
            }
            for ($index=$movedData.Count-1; $index -ge 0; $index--) {
                $move = $movedData[$index]
                if (Test-Path -LiteralPath $move.Target) { Move-Item -LiteralPath $move.Target -Destination $move.Source }
            }
            $dataRestore = Join-Path $restore 'data'
            foreach ($item in Get-ChildItem -LiteralPath $dataRestore -Force) {
                $target = Join-Path $layout.Data $item.Name
                if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
                Copy-Item -LiteralPath $item.FullName -Destination $target -Recurse
            }
            & (Join-Path $layout.App 'start.ps1') -NoBrowser
            $savedNetwork = Join-Path $layout.Data 'network.json'
            if (Test-Path -LiteralPath $savedNetwork) { Invoke-InstallerAdmin @('-NetworkSetup',('"' + $savedNetwork + '"')) }
            Write-Warning '설치에 실패하여 기존 프로그램과 DB를 복원했습니다.'
        }
    } catch {
        $keepRecovery = $true
        Write-Warning ("자동 복원에 실패했습니다. 복구 자료를 보존했습니다: " + $restore)
    }
    throw $failure
} finally {
    if ($lock) { $lock.ReleaseMutex(); $lock.Dispose() }
    if (!$keepRecovery -and (Test-Path -LiteralPath $work)) {
        $resolved = [IO.Path]::GetFullPath($work)
        $prefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\gnss-install-'
        if ($resolved.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase) -and
                !(Get-Item -LiteralPath $resolved -Force).Attributes.HasFlag([IO.FileAttributes]::ReparsePoint)) {
            Remove-Item -LiteralPath $resolved -Recurse -Force
        }
    }
}
