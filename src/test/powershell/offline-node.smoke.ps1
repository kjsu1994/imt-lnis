$ErrorActionPreference = 'Stop'
$repo = Split-Path (Split-Path (Split-Path $PSScriptRoot))
. (Join-Path $repo 'deployment\node\node-runtime.ps1')
$tokens=$null; $errors=$null
$installer = Join-Path $repo 'deployment\node\install.ps1'
$ast=[Management.Automation.Language.Parser]::ParseFile($installer,[ref]$tokens,[ref]$errors)
if ($errors) { throw ($errors -join [Environment]::NewLine) }
foreach ($function in $ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst]},$false)) {
    Invoke-Expression $function.Extent.Text
}
$case = Join-Path $repo ('build\offline-check-' + [Guid]::NewGuid().ToString('N'))
$app = Join-Path $case '한글 app'
$data = Join-Path $case '한글 data'
New-Item -ItemType Directory -Path $app,$data -Force | Out-Null
function Assert-Throws([scriptblock]$Action) {
    $failed=$false
    try { & $Action } catch { $failed=$true }
    if (!$failed) { throw 'Expected a validation failure.' }
}
try {
    $values=@{
        LNIS_NODE_ROLE='sender';LNIS_SERVER_PORT='18088'
        LNIS_NODE_BASE_URL='http://192.0.2.10:18088';LNIS_NODE_PEER_URL='http://192.0.2.11:18088'
        dtn_adapter='http://192.0.2.20:8080';LNIS_NODE_MANAGEMENT_TOKEN=('x'*32)
        LNIS_NODE_IMAGE='lnis-node:test';LNIS_STATE_DIR=(ConvertTo-NodeLinuxPath $data)
        LNIS_WSL_DISTRIBUTION='LNIS-Test';EXISTING_CUSTOM_SETTING='preserved'
    }
    Assert-InstallSettings $values
    $file=Join-Path $data '.env'
    Write-InstallEnvironment (Join-Path $repo 'deployment\node\.env.example') $file $values
    if ((Get-Content -Raw $file -Encoding UTF8) -notmatch '#.*현재 PC') { throw 'Korean env comments were lost.' }
    @{dataDirectory=$data}|ConvertTo-Json|Set-Content (Join-Path $app 'installation.json') -Encoding UTF8
    Set-Content (Join-Path $app 'docker-compose.windows-serial.yml') 'services: {}'
    Set-Content (Join-Path $data '.serial-bridge.env') 'test-only'
    if ((Read-NodeEnvironment $file).EXISTING_CUSTOM_SETTING -ne 'preserved') { throw 'Existing extra env setting lost.' }
    $context=Get-NodeContext $app
    if ($context.Data -ne $data -or $context.Distribution -ne 'LNIS-Test' -or $context.Port -ne 18088) { throw 'Installed paths/configuration mismatch.' }
    $compose=Get-NodeCompose $context
    if ($compose -notcontains (ConvertTo-NodeLinuxPath $file) -or $compose -notcontains 'docker-compose.windows-serial.yml') { throw 'START/STOP env or overlay missing.' }
    $bad=$values.Clone();$bad.LNIS_SERVER_PORT='65536';Assert-Throws { Assert-InstallSettings $bad }
    $bad=$values.Clone();$bad.LNIS_NODE_BASE_URL='http://192.0.2.10:8088';Assert-Throws { Assert-InstallSettings $bad }
    $bad=$values.Clone();$bad.LNIS_NODE_ROLE='receiver';Assert-Throws { Assert-InstallSettings $bad }
    $bad=$values.Clone();$bad.LNIS_NODE_PEER_URL=$bad.LNIS_NODE_BASE_URL;Assert-Throws { Assert-InstallSettings $bad }
    $bad=$values.Clone();$bad.LNIS_NODE_MANAGEMENT_TOKEN=('bad'+[char]10+'value');Assert-Throws { Write-InstallEnvironment (Join-Path $repo 'deployment\node\.env.example') $file $bad }
    Assert-Throws { ConvertTo-NodeLinuxPath '\\server\share' }
    Assert-Throws { Assert-InstallLocation 'C:\' }
    foreach ($path in @('C:\compose', 'C:\compose\sender', 'C:\한글 설치')) {
        if ((Assert-InstallLocation $path) -ne $path) { throw "Local installation path rejected: $path" }
    }
    foreach ($path in @('compose', '\\server\share', $env:WINDIR, (Join-Path $env:WINDIR 'Temp'),
            $env:ProgramFiles, (Join-Path $env:ProgramFiles 'GNSS'))) {
        Assert-Throws { Assert-InstallLocation $path }
    }
    if ((Assert-InstallLocation ($env:WINDIR + '-GNSS')) -ne ($env:WINDIR + '-GNSS')) {
        throw 'A folder next to Windows was incorrectly treated as a system folder.'
    }
    $notFolder = Join-Path $case 'not-a-folder'
    Set-Content -LiteralPath $notFolder 'test'
    Assert-Throws { Assert-InstallLocation $notFolder }
    $hiddenParent=Join-Path $case 'hidden-parent'
    New-Item -ItemType Directory -Path $hiddenParent -Force | Out-Null
    [IO.File]::SetAttributes($hiddenParent,([IO.File]::GetAttributes($hiddenParent) -bor [IO.FileAttributes]::Hidden))
    $hiddenInstall=Join-Path $hiddenParent 'installed'
    if ((Assert-InstallLocation $hiddenInstall) -ne $hiddenInstall) { throw 'Hidden parent such as AppData was rejected.' }
    Write-Host 'PASS: role/token/port validation, Korean env comments, installed paths and Compose arguments'

    $nativeEncoding=[Console]::OutputEncoding
    [Console]::OutputEncoding=[Text.Encoding]::GetEncoding(949)
    $script:wslFailure=$false
    function wsl.exe {
        if ([Console]::OutputEncoding.CodePage -ne 65001) { throw 'Native WSL output must be decoded as UTF-8.' }
        $script:receivedWslArguments=@($args)
        if ($script:wslFailure) {
            $global:LASTEXITCODE=1
            Write-Error 'invalid interpolation: missing closing brace'
        } else { $global:LASTEXITCODE=0; 'fixture-output' }
    }
    $output=Invoke-NodeWsl $context @('printf','%s','한글 사용자 & 공백')
    if ($output -ne 'fixture-output' -or $script:receivedWslArguments -notcontains '--exec' -or
            $script:receivedWslArguments[-1] -ne '한글 사용자 & 공백') { throw 'WSL argument boundaries were not preserved.' }
    $script:wslFailure=$true
    try { Invoke-NodeWsl $context @('docker','compose','config');throw 'WSL failure ignored.' }
    catch { if (!$_.Exception.Message.Contains('missing closing brace')) { throw 'WSL failure hid the actual Docker diagnostic.' } }
    if ($ErrorActionPreference -ne 'Stop') { throw 'WSL invocation changed the caller error preference.' }
    if ([Console]::OutputEncoding.CodePage -ne 949) { throw 'WSL invocation changed the caller codepage.' }
    [Console]::OutputEncoding=$nativeEncoding
    Remove-Item Function:\wsl.exe
    Write-Host 'PASS: direct WSL arguments, Unicode/metacharacters, underlying error and restored preference'

    $payload=Join-Path $app 'payload.txt';Set-Content $payload 'valid payload'
    @{image='lnis-node:test';imageId='sha256:test';files=@(@{path='payload.txt';sha256=(Get-FileHash $payload).Hash})} |
        ConvertTo-Json -Depth 4|Set-Content (Join-Path $app 'release.json') -Encoding UTF8
    Assert-NodeFiles $app | Out-Null
    Add-Content $payload 'changed';Assert-Throws { Assert-NodeFiles $app }
    @{image='test';imageId='sha256:test';files=@(@{path='..\outside.txt';sha256='test'})} |
        ConvertTo-Json -Depth 4|Set-Content (Join-Path $app 'release.json') -Encoding UTF8
    Assert-Throws { Assert-NodeFiles $app }
    Write-Host 'PASS: changed payload and outside-manifest paths rejected'

    $script:dockerChecks=0;$script:dockerStarts=0
    function Invoke-NodeWsl {
        param($Context,$Arguments)
        if ($Arguments -contains 'info') {
            $script:dockerChecks++
            if ($script:dockerChecks -lt 3) { throw 'Docker cold startup' }
            return 'linux'
        }
        $script:dockerStarts++
        if ($script:dockerStarts -eq 1) { throw 'systemd starting' }
    }
    Wait-NodeDocker $context
    if ($script:dockerChecks -ne 3 -or $script:dockerStarts -ne 2) { throw 'Cold Docker startup was not retried.' }
    $script:dockerClock=0
    function Get-Date {
        $script:dockerClock++
        return ([datetime]'2026-01-01').AddSeconds(70 * $script:dockerClock)
    }
    function Invoke-NodeWsl { param($Context,$Arguments) throw 'Docker never ready' }
    try { Wait-NodeDocker $context; throw 'Docker timeout ignored' }
    catch { if (!$_.Exception.Message.Contains('Docker never ready')) { throw 'Docker timeout hid the underlying cause.' } }
    Remove-Item Function:\Get-Date
    Write-Host 'PASS: cold Docker/systemd readiness retry and timeout diagnostic'

    $script:fakeProcess=$null;$script:started=0;$script:stopped=0;$script:browserUrls=@()
    $creation=[DateTime]'2026-01-01T00:00:00Z'
    function Get-CimInstance { param($ClassName,$Filter) return $script:fakeProcess }
    function Start-Process {
        param($FilePath,$ArgumentList,$WindowStyle,[switch]$PassThru,$RedirectStandardOutput,$RedirectStandardError)
        if ($FilePath -like 'http://*') { $script:browserUrls += $FilePath; return }
        if ($FilePath -ne 'wsl.exe' -or ($ArgumentList -join ' ') -ne '-d LNIS-Test --exec sleep infinity' -or $WindowStyle -ne 'Hidden') { throw 'Unexpected background command.' }
        if ($RedirectStandardOutput -ne (Join-Path $context.Data 'wsl-start.log') -or
            $RedirectStandardError -ne (Join-Path $context.Data 'wsl-error.log')) { throw 'WSL child must detach output handles.' }
        $script:started++
        $script:fakeProcess=[pscustomobject]@{Name='wsl.exe';ProcessId=43210;CreationDate=$creation;CommandLine='wsl.exe -d LNIS-Test --exec sleep infinity'}
        return [pscustomobject]@{Id=43210;HasExited=$false}
    }
    function Stop-Process { param($Id,$ErrorAction) if ($Id -ne 43210) { throw 'Unowned process stop.' };$script:stopped++ }
    if (!(Start-NodeKeepAlive $context)) { throw 'First startup not created.' }
    if (Start-NodeKeepAlive $context) { throw 'Duplicate WSL process created.' }
    if ($script:started -ne 1) { throw 'Duplicate creation count.' }
    $script:fakeProcess.CommandLine='wsl.exe -d OTHER --exec sleep infinity'
    Stop-NodeKeepAlive $context
    if ($script:stopped -ne 0) { throw 'Foreign WSL process was stopped.' }
    Start-NodeKeepAlive $context | Out-Null
    $script:fakeProcess.CreationDate=$creation.AddSeconds(1)
    Stop-NodeKeepAlive $context
    if ($script:stopped -ne 0) { throw 'Reused PID was stopped.' }
    Start-NodeKeepAlive $context|Out-Null
    Stop-NodeKeepAlive $context
    if ($script:stopped -ne 1) { throw 'Owned process not stopped.' }
    Write-Host 'PASS: hidden WSL startup, idempotence, process ownership and PID reuse protection'

    function Invoke-RestMethod {
        param($Uri,$TimeoutSec)
        if ($Uri.StartsWith('http://127.0.0.1:')) { throw 'IPv4 unavailable' }
        if ($Uri.EndsWith('/readiness')) { return @{status='UP'} }
        return @{online=$true;role='SENDER'}
    }
    if ((Get-NodeReady $context) -ne 'http://[::1]:18088') { throw 'IPv6 localhost fallback failed.' }
    Write-Host 'PASS: readiness verifies health and role with IPv6 fallback'

    $runtimeRoot=Join-Path $case 'runtime-selection'
    $runtimeData=Join-Path $runtimeRoot 'data'
    New-Item -ItemType Directory -Path $runtimeData -Force | Out-Null
    $script:runtimes=@();$script:imports=0;$script:failImport=$false
    function Get-NodeWslRuntimes { return $script:runtimes }
    function wsl.exe {
        if ($args[0] -ne '--import') { throw 'Unexpected runtime command' }
        $script:imports++
        $name=$args[1];$location=$args[2]
        New-Item -ItemType Directory -Path $location -Force | Out-Null
        Set-Content -LiteralPath (Join-Path $location 'ext4.vhdx') 'preserved-runtime'
        if ($script:failImport) { $global:LASTEXITCODE=1;return }
        $script:runtimes+=@([pscustomobject]@{Name=$name;Location=$location;Version=2})
        $global:LASTEXITCODE=0
        'WSL import completed'
    }
    $name=Initialize-NodeRuntime $runtimeRoot $runtimeData $case ''
    if ($name -ne 'LNIS-Runtime' -or $script:imports -ne 1) { throw 'Fresh runtime selection/import result is incorrect.' }
    if ((Initialize-NodeRuntime $runtimeRoot $runtimeData $case '') -ne $name -or $script:imports -ne 1) {
        throw 'Interrupted managed installation was not resumed.'
    }
    Remove-Item -LiteralPath (Join-Path $runtimeData 'runtime-registration.json')
    if ((Initialize-NodeRuntime $runtimeRoot $runtimeData $case $name) -ne $name -or $script:imports -ne 1) {
        throw 'Installed app could not recover its missing runtime record.'
    }
    $manualRoot=Join-Path $case 'manual-runtime'
    $manualData=Join-Path $manualRoot 'data'
    New-Item -ItemType Directory -Path $manualData -Force | Out-Null
    $newName=Initialize-NodeRuntime $manualRoot $manualData $case ''
    if ($newName -ne 'LNIS-Runtime-1') { throw 'Foreign/manual runtime name was not preserved.' }
    if ((Get-Content -LiteralPath (Join-Path $runtimeRoot 'runtime\ext4.vhdx')) -ne 'preserved-runtime') {
        throw 'Foreign runtime data changed.'
    }
    Remove-Item -LiteralPath (Join-Path $manualData 'runtime-registration.json')
    $third=Initialize-NodeRuntime $manualRoot $manualData $case ''
    if ($third -ne 'LNIS-Runtime-2') { throw 'Unrecorded existing runtime folders were overwritten.' }
    Set-Content -LiteralPath (Join-Path $manualData 'runtime-registration.json') '{invalid'
    $script:runtimes=@($script:runtimes|Where-Object Name -ne $third)
    $next=Initialize-NodeRuntime $manualRoot $manualData $case ''
    if ($next -ne 'LNIS-Runtime-3') { throw 'Deleted distribution/nonempty runtime was not preserved.' }
    $failureRoot=Join-Path $case 'failed-runtime'
    $failureData=Join-Path $failureRoot 'data'
    New-Item -ItemType Directory -Path $failureData -Force | Out-Null
    $script:failImport=$true
    Assert-Throws { Initialize-NodeRuntime $failureRoot $failureData $case '' }
    $failed=Get-Content -LiteralPath (Join-Path $failureData 'runtime-registration.json') -Raw | ConvertFrom-Json
    $script:failImport=$false
    $resumed=Initialize-NodeRuntime $failureRoot $failureData $case ''
    if ($resumed -eq $failed.distribution -or !(Test-Path -LiteralPath $failed.location)) {
        throw 'Retry did not preserve incomplete import data.'
    }
    Write-Host 'PASS: fresh/imported runtime, interrupted install, missing/corrupt record, foreign/manual name, missing distro and import retry'
    Remove-Item Function:\wsl.exe
    Remove-Item Function:\Get-NodeWslRuntimes

    $script:networkCalls=@();$script:foreignRule=$false
    function Get-NetIPAddress { param($AddressFamily) return @{IPAddress='192.0.2.10'} }
    function netsh.exe {
        $script:networkCalls+=($args -join ' ');$global:LASTEXITCODE=0
        if ($args -contains 'show' -and $script:foreignRule) { return '192.0.2.10 18088 172.20.0.2 18088' }
    }
    function Set-Service { param($Name,$StartupType) }
    function Start-Service { param($Name) }
    function Get-NetFirewallRule { param($Name,$ErrorAction) return @() }
    function Remove-NetFirewallRule {}
    function New-NetFirewallRule { param($Name,$DisplayName,$Direction,$Protocol,$LocalAddress,$LocalPort,$RemoteAddress,$Profile,$Action,$Program) }
    $network=[pscustomobject]@{listenAddress='192.0.2.10';port=18088;bridgePort=18765;loopback='::1';record=(Join-Path $data 'network.json');java='C:\test\runtime\bin\java.exe'}
    Set-NodeNetwork $network
    if (!($script:networkCalls | Where-Object { $_ -match 'add v4tov6.*listenport=18088.*connectaddress=::1.*connectport=18088' })) { throw 'Windows/WSL ports do not match.' }
    $script:rules='192.0.2.10 18088 ::1 18088';$script:failAdd=$false
    function netsh.exe {
        $command=$args -join ' ';$script:networkCalls+=$command;$global:LASTEXITCODE=0
        if ($args -contains 'show') { return $script:rules }
        if ($args -contains 'add' -and $script:failAdd) { $script:failAdd=$false;$global:LASTEXITCODE=1 }
    }
    $network.port=18089
    Set-NodeNetwork $network
    if (!($script:networkCalls|Where-Object { $_ -match 'delete.*listenport=18088' })) { throw 'Old owned proxy not removed.' }
    $script:rules='192.0.2.10 18089 ::1 18089';$script:failAdd=$true
    $network.port=18090
    Assert-Throws { Set-NodeNetwork $network }
    if (($script:networkCalls|Select-Object -Last 1) -notmatch 'add.*listenport=18089.*connectport=18089') { throw 'Failed update did not restore the old proxy.' }
    if ((Get-Content -Raw $network.record|ConvertFrom-Json).port -ne 18089) { throw 'Failed update overwrote ownership record.' }
    Remove-Item -LiteralPath $network.record
    $network.port=18088
    $script:rules='192.0.2.10 18088 172.20.0.2 18088'
    $script:foreignRule=$true
    Assert-Throws { Set-NodeNetwork $network }
    $network.loopback='172.20.0.2';Assert-Throws { Set-NodeNetwork $network }
    Write-Host 'PASS: matching Windows/WSL port, stable loopback proxy and foreign-rule isolation'

    $script:hostConfig='{"services":{"node":{"network_mode":"host","environment":{}}}}'
    function Invoke-NodeWsl { param($Context,$Arguments) return $script:hostConfig }
    $savedPort=$context.Port
    $context.Port=8088
    Assert-NodePortMapping $context
    $context.Port=8089
    Assert-Throws { Assert-NodePortMapping $context }
    $script:hostConfig='{"services":{"node":{"network_mode":"host","environment":{"SERVER_PORT":"8089"}}}}'
    Assert-NodePortMapping $context
    $script:hostConfig='{"services":{"node":{"ports":[{"published":"8089","target":8088,"protocol":"tcp"}],"environment":{"SERVER_PORT":"8089"}}}}'
    Assert-Throws { Assert-NodePortMapping $context }
    $context.Port=$savedPort
    Write-Host 'PASS: existing host network/default port and receiver port; mismatched published port rejected'

    $script:commands=New-Object 'Collections.Generic.List[string]'
    function Invoke-NodeWsl {
        param($Context,[string[]]$Arguments)
        $script:commands.Add(($Arguments -join ' '))
        if ($Arguments -contains 'inspect') { return 'sha256:fixture' }
        if ($Arguments -contains 'info') { return 'linux' }
        if ($Arguments -contains 'config') { return '{"services":{"node":{"ports":[{"published":"18088","target":18088,"protocol":"tcp"}],"environment":{"SERVER_PORT":"18088"}}}}' }
    }
    Set-Content (Join-Path $app 'start-serial-bridge.ps1') 'param($Distribution,$DataDirectory)'
    Set-Content (Join-Path $app 'stop-serial-bridge.ps1') 'param($DataDirectory)'
    @{image='lnis-node:test';imageId='sha256:fixture'}|ConvertTo-Json|Set-Content (Join-Path $app 'release.json') -Encoding UTF8
    $script:fakeProcess=$null;$script:started=0;$script:stopped=0
    $source=(Get-Content -Raw -LiteralPath (Join-Path $repo 'deployment\node\start.ps1') -Encoding UTF8).
        Replace(". (Join-Path "+'$PSScriptRoot'+" 'node-runtime.ps1')",'').
        Replace('$context = Get-NodeContext $PSScriptRoot','$context = $script:testContext')
    $script:testContext=$context
    & ([ScriptBlock]::Create($source)) -NoBrowser
    & ([ScriptBlock]::Create($source))
    if ($script:browserUrls.Count -ne 1 -or $script:browserUrls[0] -ne $values.LNIS_NODE_BASE_URL) {
        throw 'START opened localhost instead of the configured PC address.'
    }
    if ($script:started -ne 1) { throw 'START twice created extra WSL processes.' }
    $ups=@($script:commands|Where-Object { $_ -match ' up ' })
    if ($ups.Count -ne 2 -or ($ups|Where-Object { $_ -notmatch '--no-build --pull never' })) { throw 'START must reconcile env without build/pull.' }
    $source=(Get-Content -Raw -LiteralPath (Join-Path $repo 'deployment\node\stop.ps1') -Encoding UTF8).
        Replace(". (Join-Path "+'$PSScriptRoot'+" 'node-runtime.ps1')",'').
        Replace('$context = Get-NodeContext $PSScriptRoot','$context = $script:testContext')
    & ([ScriptBlock]::Create($source))
    if ($script:stopped -ne 1) { throw 'STOP did not close the owned WSL session.' }
    $down=@($script:commands|Where-Object { $_ -match ' down$' })
    if ($down.Count -ne 1 -or !$down[0].Contains((ConvertTo-NodeLinuxPath $context.EnvFile))) { throw 'STOP used different Compose settings.' }
    Write-Host 'PASS: actual START/STOP control flow, repeated START, env reconciliation, no build/pull, scoped stop'

    # Run the installer transaction against isolated files; OS/WSL/network calls are mocked.
    $script:bundle=Join-Path $case 'bundle'
    $bundleApp=Join-Path $script:bundle 'app'
    New-Item -ItemType Directory -Path $bundleApp,(Join-Path $script:bundle 'offline') -Force|Out-Null
    Copy-Item (Join-Path $repo 'deployment\node\.env.example') $bundleApp
    Set-Content (Join-Path $bundleApp 'start.ps1') @'
param([switch]$NoBrowser)
$fixtureData=Join-Path (Split-Path $PSScriptRoot) 'data'
if (Test-Path (Join-Path $fixtureData 'fail-once')) {
    Remove-Item (Join-Path $fixtureData 'fail-once')
    Set-Content (Join-Path $fixtureData 'DB\marker') 'changed-by-failed-upgrade'
    throw 'Injected startup failure'
}
'@
    Set-Content (Join-Path $bundleApp 'stop.ps1') @'
Set-Content (Join-Path (Split-Path $PSScriptRoot) 'data\stopped-marker') 'stopped'
'@
    Set-Content (Join-Path $bundleApp 'stop-serial-bridge.ps1') 'param($DataDirectory)'
    Copy-Item -LiteralPath (Join-Path $repo 'deployment\node\START.cmd'),(Join-Path $repo 'deployment\node\STOP.cmd') -Destination $bundleApp
    function Invoke-InstallerAdmin { param($Arguments) $script:adminCalls+=($Arguments -join ' ') }
    function Assert-NodeFiles { param($Root) return $script:release }
    $script:installWslHeld=$false;$script:installKeeps=0
    function Start-NodeKeepAlive { param($Context) $script:installWslHeld=$true;$script:installKeeps++;return $true }
    function Stop-NodeKeepAlive { param($Context) $script:installWslHeld=$false }
    function Invoke-NodeWsl {
        param($Context,$Arguments)
        if (!$script:installWslHeld) { throw 'Installer used Docker before keeping WSL running.' }
        $script:installCalls+=($Arguments -join ' ')
    }
    function Get-NodeReady { param($Context) return 'http://127.0.0.1:18088' }
    function Wait-NodeReady { param($Context) return 'http://127.0.0.1:18088' }
    function Assert-NodeIdle { param($Context,$Url) $script:idleChecks++ }
    function wsl.exe { $global:LASTEXITCODE=0;return 'LNIS-Runtime' }
    function Get-NodeWslRuntimes {
        return @([pscustomobject]@{Name='LNIS-Runtime';Location=(Join-Path $Destination 'runtime');Version=2})
    }
    function Invoke-RestMethod { param($Uri,$TimeoutSec) return @{online=$true;role='sender'} }
    $script:fakeShell=New-Object PSObject
    $script:fakeShell|Add-Member ScriptMethod CreateShortcut {
        param($Path)
        $shortcut=[pscustomobject]@{TargetPath='';WorkingDirectory=''}
        $shortcut|Add-Member ScriptMethod Save {}
        return $shortcut
    }
    $Destination=Join-Path $case 'installed'
    $script:installData=Join-Path $Destination 'data'
    New-Item -ItemType Directory -Path $script:installData -Force|Out-Null
    @{location=(Join-Path $Destination 'runtime');owner=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value}|
        ConvertTo-Json|Set-Content (Join-Path $script:installData 'runtime-registration.json') -Encoding UTF8
    $Configuration=$file;$NoBrowser=$true
    $script:release=@{image='lnis-node:fixture';imageId='sha256:fixture'}
    $installerSource=Get-Content -Raw -LiteralPath $installer -Encoding UTF8
    $transaction=$installerSource.Substring($installerSource.IndexOf('$destinationRoot = Assert-InstallLocation'))
    $transaction=$transaction.Replace('$shell = New-Object -ComObject WScript.Shell','$shell = $script:fakeShell')
    $script:adminCalls=@();$script:installCalls=@();$script:idleChecks=0;$script:breakUpgrade=$false
    & ([ScriptBlock]::Create($transaction))
    $installedContext=Get-NodeContext (Join-Path $Destination 'app')
    if (!$installedContext.Installed -or $installedContext.Data -ne $script:installData) { throw 'Fresh install path mismatch.' }
    if (!($script:installCalls|Where-Object {$_ -match 'docker load --input'})) { throw 'Offline image not loaded.' }
    if ($script:installKeeps -lt 1) { throw 'Fresh install did not hold WSL before loading the image.' }
    foreach ($name in @('START','STOP')) {
        $launcher=Get-Content -LiteralPath (Join-Path $Destination ($name+'.cmd')) -Raw -Encoding UTF8
        if (!$launcher.Contains('%~dp0app\'+$name.ToLowerInvariant()+'.ps1')) { throw 'Root launcher did not target the installed app.' }
    }
    $rootStart=Join-Path $Destination 'START.cmd'
    $savedStart=Get-Content -LiteralPath $rootStart -Raw
    [IO.File]::WriteAllText($rootStart,'user-owned launcher')
    Assert-Throws { & ([ScriptBlock]::Create($transaction)) }
    if ((Get-Content -LiteralPath $rootStart -Raw) -ne 'user-owned launcher') { throw 'An unrelated launcher was overwritten.' }
    [IO.File]::WriteAllText($rootStart,$savedStart)

    Set-Content (Join-Path $script:installData 'DB\marker') 'original-db'
    $Configuration=$null
    & ([ScriptBlock]::Create($transaction))
    if ($script:idleChecks -ne 1) { throw 'Upgrade did not check active work.' }
    if ((Get-Content (Join-Path $script:installData 'DB\marker')) -ne 'original-db') { throw 'Upgrade changed the DB.' }
    Set-Content (Join-Path $script:installData 'fail-once') ''
    Assert-Throws { & ([ScriptBlock]::Create($transaction)) }
    if ((Get-Content (Join-Path $script:installData 'DB\marker')) -ne 'original-db') { throw 'Failed upgrade did not restore the DB.' }
    if (!(Test-Path (Join-Path $Destination 'app\installation.json'))) { throw 'Failed upgrade did not restore the app.' }
    Write-Host 'PASS: actual installer fresh install, offline image load, idle upgrade, and failed-upgrade app/env/DB restoration'

    $Destination=Join-Path $case 'interrupted-first-install'
    $retryData=Join-Path $Destination 'data'
    New-Item -ItemType Directory -Path $retryData -Force|Out-Null
    @{location=(Join-Path $Destination 'runtime');owner=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value}|
        ConvertTo-Json|Set-Content (Join-Path $retryData 'runtime-registration.json') -Encoding UTF8
    Set-Content (Join-Path $retryData 'fail-once') ''
    $Configuration=$file
    Assert-Throws { & ([ScriptBlock]::Create($transaction)) }
    if (!(Test-Path (Join-Path $retryData 'stopped-marker'))) { throw 'Failed first installation did not stop its new service.' }
    $marker=Get-Content (Join-Path $retryData 'DB\marker') -Raw
    Set-Content (Join-Path $Destination 'app\stop.ps1') "throw 'Old Compose cannot be parsed'"
    function Get-NodeReady { param($Context) return $null }
    $script:installCalls=@()
    & ([ScriptBlock]::Create($transaction))
    if (!($script:installCalls | Where-Object { $_ -eq 'docker ps --quiet' })) { throw 'Interrupted install must check running containers without parsing old Compose.' }
    if ((Get-Content (Join-Path $retryData 'DB\marker') -Raw) -ne $marker) { throw 'First-install retry removed data.' }
    if (!(Test-Path (Join-Path $Destination 'app\installation.json'))) { throw 'First-install retry did not complete.' }
    Write-Host 'PASS: first installation interrupted after app deployment can be retried without deleting data'


} finally {
    $root=[IO.Path]::GetFullPath((Join-Path $repo 'build')).TrimEnd('\')+'\'
    if ([IO.Path]::GetFullPath($case).StartsWith($root) -and (Split-Path $case -Leaf).StartsWith('offline-check-')) {
        Remove-Item -LiteralPath $case -Recurse -Force
    }
}
