$ErrorActionPreference = 'Stop'
$repo = Split-Path (Split-Path (Split-Path $PSScriptRoot))
$case = Join-Path $repo ('build\setup-check-' + [Guid]::NewGuid().ToString('N'))
$bundle = Join-Path $case 'bundle'
$oldLocalAppData = $env:LOCALAPPDATA
function Assert-Throws([scriptblock]$Action) {
    $failed=$false
    try { & $Action } catch { $failed=$true }
    if (!$failed) { throw 'Expected validation failure.' }
}
try {
    $env:LOCALAPPDATA = Join-Path $case '사용자 설정'
    New-Item -ItemType Directory -Path (Join-Path $bundle 'app'),(Join-Path $bundle 'installer') -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $repo 'deployment\node\node-runtime.ps1'),(Join-Path $repo 'deployment\node\.env.example') -Destination (Join-Path $bundle 'app')
    Copy-Item -LiteralPath (Join-Path $repo 'deployment\node\setup-settings.ps1') -Destination (Join-Path $bundle 'installer')
    . (Join-Path $bundle 'installer\setup-settings.ps1')
    function Get-SetupAddresses { return @('192.0.2.10','192.0.2.20') }

    $destination = Join-Path $case '한글 설치'
    $hints = Join-Path $case 'hints.ini'
    Write-SetupHints $destination $hints
    if ((Read-SetupIni $hints 'network' 'localIp') -ne '192.0.2.10') { throw 'Local IP auto selection failed.' }
    if ((Read-SetupIni $hints 'network' 'peerIp') -ne '' -or (Read-SetupIni $hints 'network' 'peerPort') -ne '8088') { throw 'Optional peer defaults failed.' }
    if ((Read-SetupIni $hints 'setup' 'ipCount') -ne '2') { throw 'Multiple interfaces were lost.' }
    $management = Read-SetupIni $hints 'env' 'LNIS_NODE_MANAGEMENT_TOKEN'
    $receiveToken = Read-SetupIni $hints 'env' 'LNIS_DTN_RECEIVE_TOKEN'
    if ($management.Length -ne 64 -or $receiveToken.Length -ne 64 -or $management -eq $receiveToken) { throw 'Existing required-token defaults failed.' }
    $request = Join-Path $case 'request.ini'
    $config = Join-Path $case 'configuration.env'
    function Write-TestRequest([string]$Role='sender',[string]$Port='18088') {
        [IO.File]::WriteAllLines($request,@(
            '[setup]',"destination=$destination",'[env]',"LNIS_NODE_ROLE=$Role","LNIS_SERVER_PORT=$Port",
            ('LNIS_NODE_BASE_URL=http://192.0.2.10:'+$Port),'LNIS_NODE_PEER_URL=','dtn_adapter=',
            "LNIS_NODE_MANAGEMENT_TOKEN=$management",'LNIS_DTN_SEND_TOKEN=',"LNIS_DTN_RECEIVE_TOKEN=$receiveToken",
            ('LNIS_IQ_HOST_PATH='+(Join-Path $destination 'data\exchange')),'LNIS_NTP_SERVER='
        ),[Text.Encoding]::Unicode)
    }
    Write-TestRequest
    Prepare-SetupConfiguration $request $config
    $values = Read-NodeEnvironment $config
    if ($values.LNIS_NODE_PEER_URL -ne '' -or $values.dtn_adapter -ne '' -or $values.LNIS_SERVER_PORT -ne '18088') { throw 'Blank remote addresses/custom port not preserved.' }
    $pending = Get-SetupPendingPath
    if ((Get-Content -LiteralPath $pending -Raw).Contains($management)) { throw 'Pending configuration exposed plaintext credentials.' }
    $resumed = Read-SetupPending
    if ($resumed.destination -ne $destination -or $resumed.values.LNIS_NODE_MANAGEMENT_TOKEN -ne $management) { throw 'Interrupted setup settings were not restored.' }
    $resumedHints = Join-Path $case 'resume.ini'
    Write-SetupHints '' $resumedHints
    if ((Read-SetupIni $resumedHints 'setup' 'destination') -ne $destination) { throw 'Reboot resume destination lost.' }
    if (Test-Path -LiteralPath $destination) { throw 'Wizard changed the installation before confirmation.' }
    Write-Host 'PASS: automatic IPv4, optional remote addresses, Unicode input, custom service port, encrypted restart settings'

    New-Item -ItemType Directory -Path (Join-Path $destination 'data'),(Join-Path $destination 'app') -Force | Out-Null
    $values.LNIS_DB_PASSWORD='existing-only';$values.EXISTING_CUSTOM_SETTING='preserved'
    $existing = Join-Path $destination 'data\.env'
    Write-InstallEnvironment (Join-Path $bundle 'app\.env.example') $existing $values
    Set-Content -LiteralPath (Join-Path $destination 'app\installation.json') -Value '{}' -Encoding UTF8
    Write-TestRequest
    Prepare-SetupConfiguration $request $config
    $result = Read-NodeEnvironment $config
    if ($result.LNIS_DB_PASSWORD -ne 'existing-only' -or $result.EXISTING_CUSTOM_SETTING -ne 'preserved') { throw 'Existing private/custom settings were removed.' }
    $before = (Get-FileHash -LiteralPath $existing).Hash
    Write-TestRequest receiver
    Assert-Throws { Prepare-SetupConfiguration $request $config }
    Write-TestRequest sender 65536
    Assert-Throws { Prepare-SetupConfiguration $request $config }
    if ((Get-FileHash -LiteralPath $existing).Hash -ne $before) { throw 'Invalid setup changed installed settings.' }
    if ((Get-NodeShortcutName sender) -ne 'GNSS 전송 시험 · 송신' -or
            (Get-NodeShortcutName receiver) -ne 'GNSS 전송 시험 · 수신') { throw 'Role shortcut labels are incorrect.' }
    Write-Host 'PASS: existing settings preservation, role lock, invalid port, unchanged installed env, role shortcut names'

    $tokens=$null;$errors=$null
    $installerAst=[Management.Automation.Language.Parser]::ParseFile((Join-Path $repo 'deployment\node\install.ps1'),[ref]$tokens,[ref]$errors)
    $definition=$installerAst.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Install-WslPrerequisites'},$false)
    Invoke-Expression $definition.Extent.Text
    function Get-CimInstance {
        param($ClassName)
        switch ($ClassName) {
            'Win32_OperatingSystem' { return @{BuildNumber='22631'} }
            'Win32_Processor' { return @{VirtualizationFirmwareEnabled=$true} }
            'Win32_ComputerSystem' { return @{HypervisorPresent=$true} }
        }
    }
    function Get-InstalledWslVersion { return [version]'9.0.0' }
    $script:release=@{wslVersion='3.0.0'}
    $script:missingFeature=$false
    function Get-WindowsOptionalFeature { param([switch]$Online,$FeatureName) return @{State=$(if ($script:missingFeature) {'Disabled'} else {'Enabled'})} }
    function Enable-WindowsOptionalFeature { param([switch]$Online,$FeatureName,[switch]$All,[switch]$NoRestart,[switch]$LimitAccess) if (!$LimitAccess) { throw 'Offline setup must not fetch Windows features from Windows Update.' };return @{RestartNeeded=$true} }
    function Start-Process { param($FilePath,$ArgumentList,[switch]$Wait,$WindowStyle,[switch]$PassThru) return @{ExitCode=1638} }
    if (Install-WslPrerequisites $case) { throw 'Existing WSL incorrectly requires a reboot.' }
    $script:missingFeature=$true
    if (!(Install-WslPrerequisites $case)) { throw 'New Windows features must request a reboot.' }
    Write-Host 'PASS: installed WSL reuse and first-PC Windows-feature reboot requirement'

} finally {
    $env:LOCALAPPDATA=$oldLocalAppData
    $base=[IO.Path]::GetFullPath((Join-Path $repo 'build')).TrimEnd('\')+'\'
    if ([IO.Path]::GetFullPath($case).StartsWith($base) -and (Split-Path $case -Leaf).StartsWith('setup-check-')) {
        Remove-Item -LiteralPath $case -Recurse -Force
    }
}
