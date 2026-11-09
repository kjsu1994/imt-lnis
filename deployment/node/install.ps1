[CmdletBinding()]
param(
    [string]$Destination = (Join-Path $env:ProgramData 'imtplus\DTN'),
    [string]$BundleRoot,
    [string]$Configuration,
    [switch]$SystemSetup,
    [string]$NetworkSetup,
    [switch]$NoBrowser,
    [string]$LogFile
)
$ErrorActionPreference = 'Stop'


function Invoke-InstallerAdmin([string[]]$Arguments) {
    $args = @('-NoProfile','-ExecutionPolicy','Bypass','-File',('"' + $PSCommandPath + '"'),'-BundleRoot',('"' + $script:bundle + '"')) + $Arguments
    if ($LogFile) { $args += @('-LogFile', ('"' + $LogFile + '.admin.log"')) }
    $child = Start-Process powershell.exe -ArgumentList $args -Verb RunAs -WindowStyle Hidden -Wait -PassThru
    if ($child.ExitCode -eq 3010) {
        Write-Host 'Windows 구성요소를 준비했습니다. PC를 재부팅한 뒤 설치 프로그램을 다시 실행하세요.'
        exit 3010
    }
    if ($child.ExitCode -ne 0) { throw "설치 권한 작업에 실패했습니다 (종료 코드 $($child.ExitCode)). 설치 기록을 확인하세요." }
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

function Install-WslPrerequisites([string]$Offline) {
    $os = Get-CimInstance Win32_OperatingSystem
    if ([int]$os.BuildNumber -lt 22000 -or $env:PROCESSOR_ARCHITECTURE -ne 'AMD64') { throw 'Windows 11 x64가 필요합니다.' }
    $processor = Get-CimInstance Win32_Processor | Select-Object -First 1
    if (!$processor.VirtualizationFirmwareEnabled -and !(Get-CimInstance Win32_ComputerSystem).HypervisorPresent) { throw 'BIOS/UEFI의 가상화 기능을 활성화하세요.' }
    $restart = $false
    foreach ($name in @('Microsoft-Windows-Subsystem-Linux','VirtualMachinePlatform')) {
        if ((Get-WindowsOptionalFeature -Online -FeatureName $name).State -ne 'Enabled') {
            $result = Enable-WindowsOptionalFeature -Online -FeatureName $name -All -NoRestart -LimitAccess
            $restart = $restart -or $result.RestartNeeded
        }
    }
    $installedVersion = Get-InstalledWslVersion
    $requiredVersion = $null
    if ($release -and $release.wslVersion) {
        [version]::TryParse($release.wslVersion.TrimStart('v'), [ref]$requiredVersion) | Out-Null
    }
    if ($installedVersion -and $requiredVersion -and $installedVersion -ge $requiredVersion) {
        return $restart
    }
    $msi = Join-Path $Offline 'wsl.msi'
    $process = Start-Process msiexec.exe -ArgumentList @('/i',('"' + $msi + '"'),'/qn','/norestart') -Wait -WindowStyle Hidden -PassThru
    if ($process.ExitCode -notin @(0,1638,3010)) { throw "오프라인 WSL 설치 실패: $($process.ExitCode)" }
    return ($restart -or $process.ExitCode -eq 3010)
}

function Set-NodeNetwork($Config) {
    $address = $null
    if (![Net.IPAddress]::TryParse($Config.listenAddress,[ref]$address) -or
        $address.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork -or
        !(Get-NetIPAddress -AddressFamily IPv4 | Where-Object IPAddress -eq $Config.listenAddress)) {
        throw '현재 PC에 할당된 IPv4 주소를 입력하세요.'
    }
    $port = [int]$Config.port
    $bridgePort = [int]$Config.bridgePort
    if ($port -lt 1 -or $port -gt 65535 -or $bridgePort -lt 1 -or $bridgePort -gt 65535) { throw '서비스 및 중계 포트는 1~65535여야 합니다.' }
    if ($Config.loopback -notin @('127.0.0.1','::1')) { throw '포트 전달 대상은 Windows 내부 주소만 허용합니다.' }
    $mode = if ($Config.loopback -eq '::1') { 'v4tov6' } else { 'v4tov4' }
    $record = $Config.record
    $owned = if (Test-Path -LiteralPath $record) { Get-Content -Raw -LiteralPath $record -Encoding UTF8 | ConvertFrom-Json } else { $null }
    $rules = @(& netsh.exe interface portproxy show all)
    $existing = @($rules | Where-Object { $_ -match ('^\s*' + [regex]::Escape($Config.listenAddress) + '\s+' + $port + '\s+') })
    if ($existing.Count -and (!$owned -or $owned.listenAddress -ne $Config.listenAddress -or [int]$owned.port -ne $port)) {
        throw '같은 주소·포트의 기존 전달 규칙이 있습니다. 설치 담당자가 확인해야 합니다.'
    }
    $oldRule = @()
    if ($owned) {
        $oldRule = @($rules | Where-Object { $_ -match ('^\s*' + [regex]::Escape($owned.listenAddress) + '\s+' + [int]$owned.port + '\s+') })
        if ($oldRule.Count -and $oldRule[0] -notmatch ('\s' + [regex]::Escape($owned.loopback) + '\s+' + [int]$owned.port + '\s*$')) {
            throw '다른 프로그램이 포트 전달 규칙을 변경했습니다.'
        }
    }
    Set-Service iphlpsvc -StartupType Automatic
    Start-Service iphlpsvc
    $added = $false
    try {
        if ($oldRule.Count) {
            & netsh.exe interface portproxy delete $owned.mode "listenaddress=$($owned.listenAddress)" "listenport=$($owned.port)" | Out-Null
            if ($LASTEXITCODE -ne 0) { throw '기존 전달 규칙을 갱신하지 못했습니다.' }
        }
        & netsh.exe interface portproxy add $mode "listenaddress=$($Config.listenAddress)" "listenport=$port" "connectaddress=$($Config.loopback)" "connectport=$port" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Windows → WSL 포트 전달 설정에 실패했습니다.' }
        $added = $true
        foreach ($name in @(('IMT-DTN-Service-' + $port),('IMT-DTN-COM-' + $port))) {
            Get-NetFirewallRule -Name $name -ErrorAction SilentlyContinue | Remove-NetFirewallRule
        }
        New-NetFirewallRule -Name ('IMT-DTN-Service-' + $port) -DisplayName 'IMT DTN service' -Direction Inbound -Protocol TCP -LocalAddress $Config.listenAddress -LocalPort $port -RemoteAddress LocalSubnet -Profile Any -Action Allow | Out-Null
        New-NetFirewallRule -Name ('IMT-DTN-COM-' + $port) -DisplayName 'IMT DTN COM bridge' -Direction Inbound -Protocol TCP -LocalPort $bridgePort -Program $Config.java -RemoteAddress LocalSubnet -Profile Any -Action Allow | Out-Null
        if ($owned -and [int]$owned.port -ne $port) {
            foreach ($name in @(('IMT-DTN-Service-' + $owned.port),('IMT-DTN-COM-' + $owned.port))) {
                Get-NetFirewallRule -Name $name -ErrorAction SilentlyContinue | Remove-NetFirewallRule
            }
        }
        $Config | Add-Member -NotePropertyName mode -NotePropertyValue $mode -Force
        $Config | ConvertTo-Json | Set-Content -LiteralPath $record -Encoding UTF8
    } catch {
        $failure = $_
        if ($added) {
            & netsh.exe interface portproxy delete $mode "listenaddress=$($Config.listenAddress)" "listenport=$port" | Out-Null
        }
        if ($oldRule.Count) {
            & netsh.exe interface portproxy add $owned.mode "listenaddress=$($owned.listenAddress)" "listenport=$($owned.port)" "connectaddress=$($owned.loopback)" "connectport=$($owned.port)" | Out-Null
            if ($LASTEXITCODE -ne 0) { Write-Warning '이전 포트 전달 규칙 복원에 실패했습니다. 설치 로그를 확인하세요.' }
        }
        throw $failure
    }
}

function Read-InstallSecret([string]$Prompt) {
    $value = Read-Host $Prompt -AsSecureString
    $handle = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($value)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($handle) }
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
        LNIS_NODE_MANAGEMENT_TOKEN=(Read-InstallSecret '양쪽 공통 관리 토큰 (32자 이상)')
        LNIS_DTN_SEND_TOKEN=(Read-InstallSecret '송신 어댑터 인증 토큰 (없으면 Enter)')
        LNIS_DTN_RECEIVE_TOKEN=''
    }
    if ($role -eq 'receiver') { $settings.LNIS_DTN_RECEIVE_TOKEN = Read-InstallSecret '수신 어댑터가 callback에 사용할 토큰' }
    return $settings
}


if (!$BundleRoot) { $BundleRoot = Split-Path $PSScriptRoot }
$script:bundle = [IO.Path]::GetFullPath($BundleRoot)
. (Join-Path $script:bundle 'app\node-runtime.ps1')
$release = Assert-NodeFiles $script:bundle
if ($SystemSetup) {
    try {
        Start-Transcript -Path $(if ($LogFile) { $LogFile } else { Join-Path $script:bundle 'installer\install.log' }) -Append -Force | Out-Null
        if (Install-WslPrerequisites (Join-Path $script:bundle 'offline')) { exit 3010 }
        exit 0
    } catch { Write-Error $_; exit 1 }
}
if ($NetworkSetup) {
    try {
        Start-Transcript -Path $(if ($LogFile) { $LogFile } else { Join-Path $script:bundle 'installer\install.log' }) -Append -Force | Out-Null
        Set-NodeNetwork (Get-Content -Raw -LiteralPath $NetworkSetup -Encoding UTF8 | ConvertFrom-Json)
        exit 0
    } catch { Write-Error $_; exit 1 }
}
if ($LogFile) {
    New-Item -ItemType Directory -Path (Split-Path $LogFile) -Force | Out-Null
    Start-Transcript -Path $LogFile -Append -Force | Out-Null
}
Write-Host '[1/7] 설치 경로와 설정 확인'
$destinationRoot = Assert-InstallLocation $Destination
$app = Join-Path $destinationRoot 'app'
$data = Join-Path $destinationRoot 'data'
$existing = Join-Path $data '.env'
$values = Get-InstallSettings $existing $Configuration
Assert-InstallSettings $values
$values.LNIS_WSL_DISTRIBUTION = 'LNIS-Runtime'
$values.LNIS_NODE_IMAGE = $release.image
$values.LNIS_STATE_DIR = ConvertTo-NodeLinuxPath $data
if (!$values.ContainsKey('LNIS_IQ_ENABLED')) { $values.LNIS_IQ_ENABLED = [string]($values.LNIS_NODE_ROLE -eq 'sender') }
if (!$values.LNIS_IQ_HOST_PATH) { $values.LNIS_IQ_HOST_PATH = Join-Path $data 'exchange' }
if (!$values.ContainsKey('LNIS_NTP_SERVER')) { $values.LNIS_NTP_SERVER = '' }
$drive = New-Object IO.DriveInfo ([IO.Path]::GetPathRoot($destinationRoot))
$required = 12GB
if (Test-Path -LiteralPath (Join-Path $data 'DB')) {
    $required += (Get-ChildItem -LiteralPath (Join-Path $data 'DB') -File -Recurse | Measure-Object Length -Sum).Sum
}
if ($drive.AvailableFreeSpace -lt $required) { throw '설치·복원에 필요한 여유 공간이 부족합니다. 최소 12 GB와 기존 DB 크기만큼 확보하세요.' }
Write-Host '[2/7] Windows 및 WSL 구성요소 확인'
Invoke-InstallerAdmin @('-SystemSetup')
New-Item -ItemType Directory -Path $destinationRoot,$data,(Join-Path $data 'DB'),(Join-Path $data 'exchange') -Force | Out-Null
$stage = Join-Path $destinationRoot ('app.staging-' + [Guid]::NewGuid().ToString('N'))
Copy-Item -LiteralPath (Join-Path $script:bundle 'app') -Destination $stage -Recurse
Assert-NodeFiles $stage | Out-Null
$backup = $null
$context = $null
$lock = $null
$firstInstall = !(Test-Path -LiteralPath (Join-Path $app 'installation.json'))
try {
    if (Test-Path -LiteralPath (Join-Path $app 'installation.json')) {
        $context = Get-NodeContext $app
        $lock = Enter-NodeLock $context
        $url = Get-NodeReady $context
        if ($context.Settings.LNIS_NODE_ROLE -ne $values.LNIS_NODE_ROLE) { throw '기존 DB의 송신·수신 역할은 변경할 수 없습니다. 별도 경로에 새로 설치하세요.' }
        if ($url) { Assert-NodeIdle $context $url }
        else {
            $running = Invoke-NodeWsl $context (@('docker') + (Get-NodeCompose $context) + @('ps','--status','running','-q'))
            if ($running) { throw '실행 중인 기존 서비스의 상태를 확인할 수 없습니다. 로그를 확인한 뒤 다시 설치하세요.' }
        }
        & (Join-Path $app 'stop.ps1')
        $backup = Join-Path $destinationRoot ('backups\' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
        New-Item -ItemType Directory -Path $backup -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $data 'DB') -Destination $backup -Recurse
        Copy-Item -LiteralPath $existing -Destination (Join-Path $backup '.env')
        if (Test-Path -LiteralPath (Join-Path $data 'network.json')) {
            Copy-Item -LiteralPath (Join-Path $data 'network.json') -Destination $backup
        }
    }
    Write-Host '[3/7] 오프라인 WSL 실행 환경 준비'
    $distributions = (& wsl.exe --list --quiet) -replace [char]0, ''
    $registrationFile = Join-Path $data 'runtime-registration.json'
    if ($distributions -notcontains 'LNIS-Runtime') {
        @{location=(Join-Path $destinationRoot 'runtime');owner=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value} |
            ConvertTo-Json | Set-Content -LiteralPath $registrationFile -Encoding UTF8
        & wsl.exe --import LNIS-Runtime (Join-Path $destinationRoot 'runtime') (Join-Path $script:bundle 'offline\ubuntu-runtime.tar') --version 2
        if ($LASTEXITCODE -ne 0) { throw 'WSL 실행 환경을 가져오지 못했습니다. 가상화 지원과 재부팅 여부를 확인하세요.' }
    } elseif (!$context) {
        $registration = if (Test-Path -LiteralPath $registrationFile) { Get-Content -Raw -LiteralPath $registrationFile -Encoding UTF8 | ConvertFrom-Json } else { $null }
        if (!$registration -or $registration.location -ne (Join-Path $destinationRoot 'runtime') -or
            $registration.owner -ne [Security.Principal.WindowsIdentity]::GetCurrent().User.Value) {
            throw '같은 이름의 WSL 배포판이 이미 있습니다. 기존 설치 경로를 확인하세요.'
        }
    }
    @{dataDirectory=$data} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $stage 'installation.json') -Encoding UTF8
    if (Test-Path -LiteralPath $app) {
        if (!$backup) { throw '관리되지 않는 app 폴더가 있습니다. 다른 설치 경로를 사용하세요.' }
        Move-Item -LiteralPath $app -Destination (Join-Path $backup 'app')
    }
    Move-Item -LiteralPath $stage -Destination $app
    Write-InstallEnvironment (Join-Path $app '.env.example') $existing $values
    $context = Get-NodeContext $app
    $imageArchive = ConvertTo-NodeLinuxPath (Join-Path $script:bundle 'offline\service-image.tar')
    Write-Host '[4/7] Docker 및 서비스 이미지 준비'
    Invoke-NodeWsl $context @('sudo','-n','systemctl','start','docker') | Out-Null
    Invoke-NodeWsl $context @('docker','load','--input',$imageArchive) | Out-Host
    Write-Host '[5/7] 서비스 실행 및 준비 상태 확인'
    & (Join-Path $app 'start.ps1') -NoBrowser
    $localUrl = Wait-NodeReady $context
    $network = @{
        listenAddress=([Uri]$values.LNIS_NODE_BASE_URL).Host; port=$context.Port
        loopback=([Uri]$localUrl).Host.Trim('[',']'); record=(Join-Path $data 'network.json')
        bridgePort=$(if ($values.LNIS_SERIAL_BRIDGE_PORT) { [int]$values.LNIS_SERIAL_BRIDGE_PORT } elseif ($values.LNIS_NODE_ROLE -eq 'receiver') { 18766 } else { 18765 })
        java=(Join-Path $app 'runtime\bin\java.exe')
    }
    $networkFile = Join-Path $data 'network-install.json'
    $network | ConvertTo-Json | Set-Content -LiteralPath $networkFile -Encoding UTF8
    Write-Host '[6/7] Windows·WSL 접속 포트 적용'
    Invoke-InstallerAdmin @('-NetworkSetup',('"' + $networkFile + '"'))
    Remove-Item -LiteralPath $networkFile
    $public = Invoke-RestMethod ($values.LNIS_NODE_BASE_URL + '/lnis/api/v1/node') -TimeoutSec 5
    if (!$public.online -or $public.role -ine $values.LNIS_NODE_ROLE) { throw 'Windows PC 주소와 WSL 서비스 연결을 확인하지 못했습니다.' }
    Write-Host '[7/7] 바탕화면 바로가기 생성'
    $shell = New-Object -ComObject WScript.Shell
    foreach ($name in @('START','STOP')) {
        $link = $shell.CreateShortcut((Join-Path ([Environment]::GetFolderPath('Desktop')) ($(if ($name -eq 'START') { Get-NodeShortcutName $values.LNIS_NODE_ROLE } else { 'DTN STOP' }) + '.lnk')))
        $link.TargetPath = Join-Path $app ($name + '.cmd')
        $link.WorkingDirectory = $app
        if (Test-Path -LiteralPath (Join-Path $app 'program.ico')) { $link.IconLocation = Join-Path $app 'program.ico' }
        $link.Save()
    }
    $oldLinkPath = Join-Path ([Environment]::GetFolderPath('Desktop')) 'DTN START.lnk'
    if (Test-Path -LiteralPath $oldLinkPath) {
        $oldLink = $shell.CreateShortcut($oldLinkPath)
        if ($oldLink.TargetPath -ieq (Join-Path $app 'START.cmd')) { Remove-Item -LiteralPath $oldLinkPath }
    }
    Write-Host '설치 확인 완료. 이후에는 바탕화면의 송신·수신 바로가기로 실행하세요.'
    if (!$NoBrowser) { Start-Process $localUrl }
} catch {
    $failure = $_
    if ($backup -and (Test-Path -LiteralPath (Join-Path $backup 'app'))) {
        try {
            & (Join-Path $app 'stop.ps1')
            $failed = Join-Path $destinationRoot ('app.failed-' + [Guid]::NewGuid().ToString('N'))
            Move-Item -LiteralPath $app -Destination $failed
            Copy-Item -LiteralPath (Join-Path $backup 'app') -Destination $app -Recurse
            Copy-Item -LiteralPath (Join-Path $backup '.env') -Destination $existing -Force
            $dbFailed = Join-Path $data ('DB.failed-' + [Guid]::NewGuid().ToString('N'))
            Move-Item -LiteralPath (Join-Path $data 'DB') -Destination $dbFailed
            Copy-Item -LiteralPath (Join-Path $backup 'DB') -Destination (Join-Path $data 'DB') -Recurse
            & (Join-Path $app 'start.ps1') -NoBrowser
            $savedNetwork = Join-Path $backup 'network.json'
            if (Test-Path -LiteralPath $savedNetwork) {
                Invoke-InstallerAdmin @('-NetworkSetup',('"' + $savedNetwork + '"'))
            }
            Write-Warning '갱신에 실패하여 이전 서비스와 DB를 복원했습니다.'
        } catch { Write-Warning "자동 복원 실패. 백업 위치: $backup" }
    } elseif ($firstInstall -and $context -and $context.Installed) {
        try { & (Join-Path $app 'stop.ps1') }
        catch { Write-Warning '새 서비스 정리가 완료되지 않았습니다. 설치 기록을 확인하세요.' }
    }
    throw $failure
} finally {
    if ($lock) { $lock.ReleaseMutex(); $lock.Dispose() }
}
