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

    $defaultHints = Join-Path $case 'default.ini'
    Write-SetupHints '' $defaultHints
    if ((Read-SetupIni $defaultHints 'setup' 'destination') -ne (Join-Path $env:SystemDrive 'compose')) {
        throw 'Default local installation path is incorrect.'
    }
    $destination = Join-Path $case '한글 설치'
    $hints = Join-Path $case 'hints.ini'
    Write-SetupHints $destination $hints
    if ((Read-SetupIni $hints 'network' 'localIp') -ne '192.0.2.10') { throw 'Local IP auto selection failed.' }
    if ((Read-SetupIni $hints 'network' 'peerIp') -ne '' -or (Read-SetupIni $hints 'network' 'peerPort') -ne '8088') { throw 'Optional peer defaults failed.' }
    if ((Read-SetupIni $hints 'setup' 'ipCount') -ne '2') { throw 'Multiple interfaces were lost.' }
    $management = Read-SetupIni $hints 'env' 'LNIS_NODE_MANAGEMENT_TOKEN'
    $receiveToken = Read-SetupIni $hints 'env' 'LNIS_DTN_RECEIVE_TOKEN'
    if ($management -ne 'imt123' -or $receiveToken -ne 'imt123' -or (Read-SetupIni $hints 'env' 'LNIS_DTN_SEND_TOKEN') -ne 'imt123') { throw 'Fixed service/adapter token defaults failed.' }
    $request = Join-Path $case 'request.ini'
    $config = Join-Path $case 'configuration.env'
    $listener = New-Object Net.Sockets.TcpListener ([Net.IPAddress]::Loopback),0
    $listener.Start()
    $testPort = [string]$listener.LocalEndpoint.Port
    $listener.Stop()
    function Write-TestRequest([string]$Role='sender',[string]$Port=$testPort,[string]$ReplaceProxy='0') {
        [IO.File]::WriteAllLines($request,@(
            '[setup]',"destination=$destination","replacePortProxy=$ReplaceProxy",'[env]',"LNIS_NODE_ROLE=$Role","LNIS_SERVER_PORT=$Port",
            ('LNIS_NODE_BASE_URL=http://192.0.2.10:'+$Port),'LNIS_NODE_PEER_URL=','dtn_adapter=',
            "LNIS_NODE_MANAGEMENT_TOKEN=$management",'LNIS_DTN_SEND_TOKEN=',"LNIS_DTN_RECEIVE_TOKEN=$receiveToken",
            ('LNIS_IQ_HOST_PATH='+(Join-Path $destination 'data\exchange'))
        ),[Text.Encoding]::Unicode)
    }
    Write-TestRequest
    Prepare-SetupConfiguration $request $config
    $values = Read-NodeEnvironment $config
    if ($values.LNIS_IQ_ENABLED -ine 'true') { throw 'A new sender must keep I/Q generation enabled by default.' }
    if ($values.LNIS_NODE_PEER_URL -ne '' -or $values.dtn_adapter -ne '' -or $values.LNIS_SERVER_PORT -ne $testPort) { throw 'Blank remote addresses/custom port not preserved.' }
    $savedProxyLookup = ${function:Get-NodePortProxyRules}
    $script:proxyFixture = @([pscustomobject]@{listenAddress='10.213.14.53';port=[int]$testPort})
    function Get-NodePortProxyRules { param([int]$Port) return $script:proxyFixture }
    Write-TestRequest
    Assert-Throws { Prepare-SetupConfiguration $request $config }
    Write-TestRequest sender $testPort 1
    Prepare-SetupConfiguration $request $config
    $listener.Start()
    try {
        $busyPort = [string]$listener.LocalEndpoint.Port
        $script:proxyFixture = @()
        Write-TestRequest sender $busyPort 1
        Assert-Throws { Prepare-SetupConfiguration $request $config }
    } finally { $listener.Stop() }
    Set-Item Function:\Get-NodePortProxyRules $savedProxyLookup
    Write-TestRequest
    Prepare-SetupConfiguration $request $config
    Write-Host 'PASS: wizard requires proxy replacement consent and still rejects an application listener'
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
    $manual=$installerAst.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Get-InstallSettings'},$false)
    Invoke-Expression $manual.Extent.Text
    function Read-Host { param($Prompt) return $script:manualAnswers.Dequeue() }
    function Read-InstallSecret { param($Prompt) if($Prompt -like '*관리*'){return 'x'*32};return 'adapter-token' }
    foreach($role in @('sender','receiver')){
        $script:manualAnswers=New-Object 'Collections.Generic.Queue[string]'
        foreach($answer in @($role,'18090','192.0.2.10','','')){$script:manualAnswers.Enqueue($answer)}
        $manualValues=Get-InstallSettings (Join-Path $case 'not-installed.env') ''
        Assert-InstallSettings $manualValues
        if($manualValues.LNIS_NODE_ROLE -ne $role -or $manualValues.LNIS_NODE_BASE_URL -ne 'http://192.0.2.10:18090' -or
            $manualValues.LNIS_NODE_PEER_URL -ne '' -or $manualValues.dtn_adapter -ne ''){
            throw 'Manual command-line role/address input did not preserve optional blank addresses.'
        }
    }
    Remove-Item Function:\Read-Host
    Remove-Item Function:\Read-InstallSecret
    Write-Host 'PASS: manual INSTALL settings for sender/receiver with optional blank addresses'

    $readyDefinition=$installerAst.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Test-NodeWslReady'},$false)
    Invoke-Expression $readyDefinition.Extent.Text
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
    $script:existingWsl=$false
    function Get-NodeWslRuntimes { if ($script:existingWsl) { return @{Name='Ubuntu';Version=2} };return @() }
    function wsl.exe { $global:LASTEXITCODE=0 }
    $script:wslVersion=[version]'2.6.3'
    function Get-InstalledWslVersion { return $script:wslVersion }
    $script:release=@{wslVersion='3.0.0'}
    $script:missingFeature=$false
    function Get-WindowsOptionalFeature { param([switch]$Online,$FeatureName) return @{State=$(if ($script:missingFeature) {'Disabled'} else {'Enabled'})} }
    function Enable-WindowsOptionalFeature { param([switch]$Online,$FeatureName,[switch]$All,[switch]$NoRestart,[switch]$LimitAccess) if (!$LimitAccess) { throw 'Offline setup must not fetch Windows features from Windows Update.' };return @{RestartNeeded=$true} }
    $script:msiRuns=0;$script:msiExit=0
    function Start-Process {
        param($FilePath,$ArgumentList,[switch]$Wait,$WindowStyle,[switch]$PassThru)
        $script:msiRuns++
        return @{ExitCode=$script:msiExit}
    }
    if (Install-WslPrerequisites $case) { throw 'Existing WSL incorrectly requires a reboot.' }
    $script:existingWsl=$true
    $script:missingFeature=$true
    if (Install-WslPrerequisites $case) { throw 'Working WSL should skip redundant Windows feature setup.' }
    $script:existingWsl=$false
    $script:missingFeature=$true
    if (!(Install-WslPrerequisites $case)) { throw 'New Windows features must request a reboot.' }
    if ($script:msiRuns -ne 0) { throw 'A supported WSL installation must not be updated just to match the bundled MSI.' }
    $script:missingFeature=$false
    $script:wslVersion=[version]'9.0.0'
    if (Install-WslPrerequisites $case) { throw 'A newer installed WSL incorrectly requires a reboot.' }
    $script:wslVersion=[version]'0.66.0'
    Install-WslPrerequisites $case | Out-Null
    if ($script:msiRuns -ne 1) { throw 'A WSL version without systemd support must be updated.' }
    $script:wslVersion=$null;$script:msiExit=3010
    if (!(Install-WslPrerequisites $case)) { throw 'A new WSL installation must propagate the MSI reboot requirement.' }
    $script:msiExit=1603
    Assert-Throws { Install-WslPrerequisites $case }
    Write-Host 'PASS: supported WSL reuse, unsupported/missing WSL install, reboot and MSI failure'

    $packageAst=[Management.Automation.Language.Parser]::ParseFile((Join-Path $repo 'scripts\package-windows.ps1'),[ref]$tokens,[ref]$errors)
    $assignment=$packageAst.Find({param($node) $node -is [Management.Automation.Language.AssignmentStatementAst] -and $node.Left.Extent.Text -eq '$compose'},$true)
    if (!$assignment) { throw 'Compose packaging expression not found.' }
    $packaged=& { param($repo,$expression) Invoke-Expression $expression;return $compose } $repo $assignment.Extent.Text
    $source=Get-Content -LiteralPath (Join-Path $repo 'deployment\node\docker-compose.yml') -Raw -Encoding UTF8
    foreach ($line in $source.Split([char]10) | Where-Object { $_ -match 'LNIS_NODE_(ROLE|BASE_URL|MANAGEMENT_TOKEN):' }) {
        if (!$packaged.Contains($line.TrimEnd([char]13))) { throw 'Packaging damaged Korean text or required Compose interpolation.' }
    }
    Write-Host 'PASS: actual packaging expression preserves UTF-8 Compose values and closing braces'

    $setupPackageAst=[Management.Automation.Language.Parser]::ParseFile((Join-Path $repo 'scripts\package-setup.ps1'),[ref]$tokens,[ref]$errors)
    $replace=$setupPackageAst.Find({param($node) $node -is [Management.Automation.Language.IfStatementAst] -and $node.Extent.Text.Contains('[IO.File]::Replace(')},$true)
    if (!$replace) { throw 'Atomic package replacement not found.' }
    $Output=Join-Path $case 'Setup.exe';$pending=$Output+'.pending.exe'
    foreach ($text in @('first package','replacement package')) {
        [IO.File]::WriteAllText($pending,$text)
        Invoke-Expression $replace.Extent.Text
        if ([IO.File]::ReadAllText($Output) -ne $text -or (Test-Path -LiteralPath $pending)) {
            throw 'Package replacement failed.'
        }
    }
    if (Get-ChildItem -LiteralPath $case -Filter 'Setup.exe.previous-*') { throw 'Old package backup was not removed.' }
    Write-Host 'PASS: initial and replacement Setup.exe publication'

    $installer = Join-Path $repo 'deployment\node\install.ps1'
    $payload = Join-Path $bundle 'app\node-runtime.ps1'
    @{image='test';imageId='sha256:test';files=@(@{path='app/node-runtime.ps1';sha256=(Get-FileHash $payload).Hash})} |
        ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $bundle 'release.json') -Encoding UTF8
    $failureLog = Join-Path $case 'install.log'
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $installer -BundleRoot $bundle -Destination 'C:\' -LogFile $failureLog 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 1) { throw 'An invalid install path must return a failure exit code.' }
    $summary = Get-Content -LiteralPath ($failureLog + '.error.txt') -Raw -Encoding Unicode
    if ($summary -notmatch '\[1/7\]' -or !$summary.Contains('C:\compose')) {
        throw 'Installer did not report the failing stage and path guidance.'
    }
    $missingNetwork = Join-Path $case 'missing-network.json'
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $installer -BundleRoot $bundle -NetworkSetup $missingNetwork -LogFile $failureLog 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 1) { throw 'A failed network setup must return a failure exit code.' }
    $summary = Get-Content -LiteralPath ($failureLog + '.error.txt') -Raw -Encoding Unicode
    if ($summary -notmatch '\[6/7\]' -or !$summary.Contains('missing-network.json') -or $summary.Contains('[1/7]')) {
        throw 'Installer failure summary was stale or omitted the actual cause.'
    }
    foreach ($name in @('Invoke-InstallerAdmin','Write-InstallFailure','Set-InstallStage')) {
        $definition=$installerAst.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name},$false)
        Invoke-Expression $definition.Extent.Text
    }
    $LogFile=Join-Path $case 'parent.log'
    $PSCommandPath=$installer
    [IO.File]::WriteAllText($LogFile+'.admin.log.error.txt',"[2/7] WSL"+[Environment]::NewLine+'MSI failure 1603',(New-Object Text.UnicodeEncoding $false,$false))
    function Start-Process { param($FilePath,$ArgumentList,$Verb,$WindowStyle,[switch]$Wait,[switch]$PassThru) return @{ExitCode=1} }
    try { Invoke-InstallerAdmin @('-SystemSetup'); throw 'Admin failure was ignored.' }
    catch { if (!$_.Exception.Message.Contains('MSI failure 1603')) { throw 'Actual administrator failure was not propagated.' } }
    Write-Host 'PASS: actual installer stage/exit code, refreshed Unicode error summary, and administrator cause propagation'

} finally {
    $env:LOCALAPPDATA=$oldLocalAppData
    $base=[IO.Path]::GetFullPath((Join-Path $repo 'build')).TrimEnd('\')+'\'
    if ([IO.Path]::GetFullPath($case).StartsWith($base) -and (Split-Path $case -Leaf).StartsWith('setup-check-')) {
        Remove-Item -LiteralPath $case -Recurse -Force
    }
}
