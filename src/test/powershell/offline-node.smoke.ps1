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
    Copy-Item -LiteralPath (Join-Path $repo 'deployment\node\.env.example') -Destination $app
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
    Set-Content -LiteralPath (Join-Path $data '.serial-bridge.env') 'LNIS_SERIAL_BRIDGE_URL=http://192.0.2.10:18888'
    if ((Get-NodeBridgePort ([pscustomobject]@{Data=$data;Settings=$values})) -ne 18888) { throw 'Effective custom bridge port was not preserved.' }
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
        return @{online=$true;role=$context.Settings.LNIS_NODE_ROLE}
    }
    if ((Get-NodeReady $context) -ne 'http://[::1]:18088') { throw 'IPv6 localhost fallback failed.' }
    Write-Host 'PASS: readiness verifies health and role with IPv6 fallback'

    $runtimeRoot=Join-Path $case 'runtime-selection'
    $runtimeData=Join-Path $runtimeRoot 'data'
    New-Item -ItemType Directory -Path $runtimeData -Force | Out-Null
    $script:imports=0;$script:failImport=$false
    $ubuntu=[pscustomobject]@{Name='Ubuntu';Location='C:\existing-ubuntu';Version=2;IsDefault=$true}
    $private=[pscustomobject]@{Name='LNIS-Runtime';Location='C:\old-runtime';Version=2;IsDefault=$false}
    $script:runtimes=@($private,$ubuntu)
    function Get-NodeWslRuntimes { return $script:runtimes }
    function wsl.exe {
        if ($args[0] -ne '--import' -or $args[1] -ne 'Ubuntu') { throw 'Unexpected runtime import' }
        $script:imports++
        New-Item -ItemType Directory -Path $args[2] -Force|Out-Null
        Set-Content -LiteralPath (Join-Path $args[2] 'ext4.vhdx') 'preserved'
        if ($script:failImport) {$global:LASTEXITCODE=1;return}
        $script:runtimes+=@([pscustomobject]@{Name='Ubuntu';Location=$args[2];Version=2;IsDefault=$true})
        $global:LASTEXITCODE=0
        'import completed'
    }
    if ((Initialize-NodeRuntime $runtimeRoot $runtimeData $case 'LNIS-Runtime') -ne 'Ubuntu' -or $script:imports) { throw 'Existing default Docker WSL was not reused.' }
    Set-Content -LiteralPath (Join-Path $runtimeData 'runtime-registration.json') '{invalid'
    if ((Initialize-NodeRuntime $runtimeRoot $runtimeData $case '') -ne 'Ubuntu' -or $script:imports) { throw 'Stale installation record blocked existing Ubuntu.' }
    $debian=[pscustomobject]@{Name='Debian';Location='C:\existing-debian';Version=2;IsDefault=$false}
    $script:runtimes+=@($debian)
    if ((Initialize-NodeRuntime $runtimeRoot $runtimeData $case 'Debian') -ne 'Debian') { throw 'Configured existing distro was ignored.' }
    $ubuntu.IsDefault=$false
    Assert-Throws { Initialize-NodeRuntime $runtimeRoot $runtimeData $case '' }
    $script:runtimes=@($private)
    $script:failImport=$true
    Assert-Throws { Initialize-NodeRuntime $runtimeRoot $runtimeData $case '' }
    $script:failImport=$false
    if ((Initialize-NodeRuntime $runtimeRoot $runtimeData $case '') -ne 'Ubuntu') { throw 'Fresh PC offline Ubuntu retry failed.' }
    if (!(Test-Path -LiteralPath (Join-Path $runtimeRoot 'wsl\ext4.vhdx'))) { throw 'Partial runtime data was deleted.' }
    $script:runtimes=@([pscustomobject]@{Name='Ubuntu';Location='C:\old';Version=1;IsDefault=$true})
    Assert-Throws { Initialize-NodeRuntime $runtimeRoot $runtimeData $case '' }
    Remove-Item Function:\wsl.exe
    Remove-Item Function:\Get-NodeWslRuntimes
    $context.Settings.LNIS_COMPOSE_PROJECT='gnss-isolated'
    if ((Get-NodeCompose $context) -notcontains 'gnss-isolated') { throw 'Shared Docker project isolation missing.' }
    $context.Settings.LNIS_COMPOSE_PROJECT='bad project'
    Assert-Throws { Get-NodeCompose $context }
    $context.Settings.Remove('LNIS_COMPOSE_PROJECT')
    Write-Host 'PASS: default/shared WSL, explicit distro, private runtime exclusion, fresh Ubuntu retry, WSL1 protection and Compose isolation'

    function Get-NodeAddresses { return @('192.0.2.10') }
    $savedIdle=(Get-Command Assert-NodeIdle).ScriptBlock
    $script:addressIdleChecks=0
    function Assert-NodeIdle { param($Context,$Url) $script:addressIdleChecks++ }
    $beforeHash=(Get-FileHash $context.EnvFile).Hash
    Update-NodeAddress $context | Out-Null
    if ((Get-FileHash $context.EnvFile).Hash -ne $beforeHash) { throw 'Unchanged IP rewrote configuration.' }
    function Get-NodeAddresses { return @('192.0.2.99') }
    Update-NodeAddress $context | Out-Null
    if ($context.Settings.LNIS_NODE_BASE_URL -ne 'http://192.0.2.99:18088' -or
        (Read-NodeEnvironment $context.EnvFile).LNIS_NODE_BASE_URL -ne 'http://192.0.2.99:18088') { throw 'Changed IP/port was not persisted.' }
    function Get-NodeAddresses { return @('192.0.2.100') }
    function Assert-NodeIdle { param($Context,$Url) throw 'Busy capture' }
    $beforeHash=(Get-FileHash $context.EnvFile).Hash
    Assert-Throws { Update-NodeAddress $context }
    if ((Get-FileHash $context.EnvFile).Hash -ne $beforeHash) { throw 'Busy work changed configuration.' }
    function Get-NodeAddresses { return @('192.0.2.10') }
    function Assert-NodeIdle { param($Context,$Url) }
    Update-NodeAddress $context | Out-Null
    Set-Item Function:\Assert-NodeIdle $savedIdle
    Write-Host 'PASS: current IP refresh, preserved service port, unchanged-IP no-op and active-work protection'

    $script:networkCalls=@();$script:foreignRule=$false
    function Get-NetIPAddress { param($AddressFamily) return [pscustomobject]@{IPAddress='192.0.2.10'} }
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
    $network.listenAddress='0.0.0.0';$network.loopback='::1'
    Assert-Throws { Set-NodeNetwork $network }
    $script:rules='192.0.2.10 18088 127.0.0.1 18088'
    Assert-Throws { Set-NodeNetwork $network -ReplaceStaleProxy }
    $script:rules=@('10.213.14.53 18088 127.0.0.1 18088','10.213.14.53 18091 127.0.0.1 18091')
    Assert-Throws { Set-NodeNetwork $network }
    $script:networkCalls=@();$script:failAdd=$true
    Assert-Throws { Set-NodeNetwork $network -ReplaceStaleProxy }
    if (($script:networkCalls | Select-Object -Last 1) -notmatch 'add v4tov4.*listenaddress=10.213.14.53.*listenport=18088.*connectaddress=127.0.0.1') {
        throw 'Failed installation did not restore the stale proxy.'
    }
    $script:networkCalls=@()
    Set-NodeNetwork $network -ReplaceStaleProxy
    $deletes=@($script:networkCalls | Where-Object { $_ -match ' delete ' })
    if ($deletes.Count -ne 1 -or $deletes[0] -notmatch 'delete v4tov4.*listenaddress=10.213.14.53.*listenport=18088') {
        throw 'Installer must replace only the stale proxy on the selected port.'
    }
    if (!($script:networkCalls|Where-Object { $_ -match 'add v4tov6.*listenaddress=0.0.0.0.*connectport=18088' })) {
        throw 'IP-independent listener was not configured.'
    }
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
        if ($Arguments -contains 'inspect') { return $script:inspectedImageId }
        if ($Arguments -contains 'info') { return 'linux' }
        if ($Arguments -contains 'config') { return '{"services":{"node":{"ports":[{"published":"18088","target":18088,"protocol":"tcp"}],"environment":{"SERVER_PORT":"18088"}}}}' }
    }
    Set-Content (Join-Path $app 'start-serial-bridge.ps1') 'param($Distribution,$DataDirectory)'
    Set-Content (Join-Path $app 'stop-serial-bridge.ps1') 'param($DataDirectory)'
    @{image='lnis-node:test';imageId='sha256:fixture';imageConfigId='sha256:config'}|ConvertTo-Json|Set-Content (Join-Path $app 'release.json') -Encoding UTF8
    $script:fakeProcess=$null;$script:started=0;$script:stopped=0
    $source=(Get-Content -Raw -LiteralPath (Join-Path $repo 'deployment\node\start.ps1') -Encoding UTF8).
        Replace(". (Join-Path "+'$PSScriptRoot'+" 'node-runtime.ps1')",'').
        Replace('$context = Get-NodeContext $PSScriptRoot','$context = $script:testContext')
    function Get-NodeAddresses { return @('192.0.2.10') }
    function Repair-NodeNetwork { param($Context,$LocalUrl) }
    $script:testContext=$context
    $script:inspectedImageId='sha256:fixture'
    & ([ScriptBlock]::Create($source)) -NoBrowser
    $script:inspectedImageId='sha256:config'
    & ([ScriptBlock]::Create($source))
    if ($script:browserUrls.Count -ne 1 -or $script:browserUrls[0] -ne $values.LNIS_NODE_BASE_URL) {
        throw 'START opened localhost instead of the configured PC address.'
    }
    if ($script:started -ne 1) { throw 'START twice created extra WSL processes.' }
    $ups=@($script:commands|Where-Object { $_ -match ' up ' })
    if ($ups.Count -ne 2 -or ($ups|Where-Object { $_ -notmatch '--no-build --pull never' })) { throw 'START must reconcile env without build/pull.' }
    Set-Content (Join-Path $app 'start-serial-bridge.ps1') 'throw "[GNSS_CONFIG] Receiver must not start COM"'
    $context.Settings.LNIS_NODE_ROLE='receiver'
    & ([ScriptBlock]::Create($source)) -NoBrowser
    $receiverBridge=Read-NodeEnvironment (Join-Path $context.Data '.serial-bridge.env')
    if ($receiverBridge.LNIS_SERIAL_BRIDGE_URL -or $receiverBridge.LNIS_SERIAL_BRIDGE_TOKEN) {
        throw 'Receiver retained a COM bridge connection.'
    }
    $context.Settings.LNIS_NODE_ROLE='sender'
    Set-Content (Join-Path $app 'start-serial-bridge.ps1') 'param($Distribution,$DataDirectory)'
    Write-Host 'PASS: receiver START skips COM bridge and clears legacy bridge connection'
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
$fixtureData=(Get-Content (Join-Path $PSScriptRoot 'installation.json') -Raw|ConvertFrom-Json).dataDirectory
if (Test-Path (Join-Path $fixtureData 'fail-once')) {
    Remove-Item (Join-Path $fixtureData 'fail-once')
    Set-Content (Join-Path $fixtureData 'DB\marker') 'changed-by-failed-upgrade'
    throw 'Injected startup failure'
}
'@
    Set-Content (Join-Path $bundleApp 'stop.ps1') @'
$fixtureData=(Get-Content (Join-Path $PSScriptRoot 'installation.json') -Raw|ConvertFrom-Json).dataDirectory
Set-Content (Join-Path $fixtureData 'stopped-marker') 'stopped'
'@
    Set-Content (Join-Path $bundleApp 'stop-serial-bridge.ps1') 'param($DataDirectory)'
    Copy-Item -LiteralPath (Join-Path $repo 'deployment\node\START.cmd'),(Join-Path $repo 'deployment\node\STOP.cmd') -Destination $bundleApp
    function Test-NodeWslReady { return $false }
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
        return @([pscustomobject]@{Name='Ubuntu';Location=(Join-Path $Destination 'runtime');Version=2;IsDefault=$true})
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
    $script:installData=$Destination
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
    $installedContext=Get-NodeContext $Destination
    if (!$installedContext.Installed -or $installedContext.Data -ne $script:installData) { throw 'Fresh install path mismatch.' }
    if (!$installedContext.Settings.LNIS_SERIAL_BRIDGE_PORT -or
        [int]$installedContext.Settings.LNIS_SERIAL_BRIDGE_PORT -eq $installedContext.Port) { throw 'Fresh bridge port not isolated.' }

    if (!($script:installCalls|Where-Object {$_ -match 'docker load --input'})) { throw 'Offline image not loaded.' }
    if ($script:installKeeps -lt 1) { throw 'Fresh install did not hold WSL before loading the image.' }
    foreach ($name in @('START','STOP')) {
        $launcher=Get-Content -LiteralPath (Join-Path $Destination ($name+'.cmd')) -Raw -Encoding UTF8
        if (!$launcher.Contains('%~dp0'+$name.ToLowerInvariant()+'.ps1')) { throw 'Root launcher did not target the installed app.' }
    }
    if (Test-Path (Join-Path $Destination 'backups')) { throw 'Installation left a backups folder.' }
    if (Test-Path (Join-Path $Destination 'app')) { throw 'Fresh installation is not flat.' }
    Set-Content (Join-Path $script:installData 'DB\marker') 'original-db'
    $Configuration=$null
    & ([ScriptBlock]::Create($transaction))
    if ($script:idleChecks -ne 1) { throw 'Upgrade did not check active work.' }
    if ((Get-Content (Join-Path $script:installData 'DB\marker')) -ne 'original-db') { throw 'Upgrade changed the DB.' }
    Set-Content (Join-Path $script:installData 'fail-once') ''
    Assert-Throws { & ([ScriptBlock]::Create($transaction)) }
    if ((Get-Content (Join-Path $script:installData 'DB\marker')) -ne 'original-db') { throw 'Failed upgrade did not restore the DB.' }
    if (!(Test-Path (Join-Path $Destination 'installation.json'))) { throw 'Failed upgrade did not restore the app.' }
    Write-Host 'PASS: actual installer fresh install, offline image load, idle upgrade, and failed-upgrade app/env/DB restoration'

    $Destination=Join-Path $case 'interrupted-first-install'
    $retryData=$Destination
    New-Item -ItemType Directory -Path $retryData -Force|Out-Null
    @{location=(Join-Path $Destination 'runtime');owner=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value}|
        ConvertTo-Json|Set-Content (Join-Path $retryData 'runtime-registration.json') -Encoding UTF8
    Set-Content (Join-Path $retryData 'fail-once') ''
    $Configuration=$file
    Assert-Throws { & ([ScriptBlock]::Create($transaction)) }
    if (!(Test-Path (Join-Path $retryData 'stopped-marker'))) { throw 'Failed first installation did not stop its new service.' }
    $marker=Get-Content (Join-Path $retryData 'DB\marker') -Raw
    Set-Content (Join-Path $Destination 'stop.ps1') "throw 'Old Compose cannot be parsed'"
    $legacyValues=Read-NodeEnvironment (Join-Path $retryData '.env')
    $legacyValues.Remove('LNIS_COMPOSE_PROJECT')
    Write-InstallEnvironment (Join-Path $bundleApp '.env.example') (Join-Path $retryData '.env') $legacyValues
    function Get-NodeReady { param($Context) return $null }
    $script:installCalls=@()
    & ([ScriptBlock]::Create($transaction))
    if (!($script:installCalls | Where-Object { $_ -like 'docker ps --quiet --filter label=com.docker.compose.project.working_dir=*' })) { throw 'Interrupted install must check running containers without parsing old Compose.' }
    if ((Get-Content (Join-Path $retryData 'DB\marker') -Raw) -ne $marker) { throw 'First-install retry removed data.' }
    if (!(Test-Path (Join-Path $Destination 'installation.json'))) { throw 'First-install retry did not complete.' }
    Write-Host 'PASS: first installation interrupted after app deployment can be retried without deleting data'

    foreach ($failMigration in @($false,$true)) {
        $Destination=Join-Path $case ('legacy-' + $failMigration)
        $legacyApp=Join-Path $Destination 'app'
        $legacyData=Join-Path $Destination 'data'
        New-Item -ItemType Directory -Path $Destination,$legacyData,(Join-Path $legacyData 'DB'),(Join-Path $legacyData 'exchange') -Force | Out-Null
        Copy-Item -LiteralPath $bundleApp -Destination $legacyApp -Recurse
        Copy-Item -LiteralPath $file -Destination (Join-Path $legacyData '.env')
        @{dataDirectory=$legacyData}|ConvertTo-Json|Set-Content (Join-Path $legacyApp 'installation.json') -Encoding UTF8
        $listed=@(Get-ChildItem -LiteralPath $legacyApp -File -Recurse -Force|ForEach-Object{
            @{path=$_.FullName.Substring($legacyApp.Length+1).Replace('\','/');sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}
        })
        @{image='lnis-node:fixture';imageId='sha256:fixture';files=$listed}|ConvertTo-Json -Depth 6|
            Set-Content (Join-Path $legacyApp 'release.json') -Encoding UTF8
        Set-Content (Join-Path $legacyData 'DB\marker') 'legacy-db'
        Set-Content (Join-Path $legacyData 'exchange\sample.bin') 'iq-file'
        if ($failMigration) { Set-Content (Join-Path $legacyData 'fail-once') '' }
        $Configuration=$null
        if ($failMigration) {
            Assert-Throws { & ([ScriptBlock]::Create($transaction)) }
            if ((Get-Content (Join-Path $legacyData 'DB\marker')) -ne 'legacy-db' -or
                (Get-Content (Join-Path $legacyData 'exchange\sample.bin')) -ne 'iq-file' -or
                !(Test-Path (Join-Path $legacyApp 'installation.json'))) { throw 'Legacy migration rollback lost data/code.' }
        } else {
            & ([ScriptBlock]::Create($transaction))
            if ((Get-Content (Join-Path $Destination 'DB\marker')) -ne 'legacy-db' -or
                (Get-Content (Join-Path $Destination 'exchange\sample.bin')) -ne 'iq-file' -or
                !(Test-Path (Join-Path $Destination '.env'))) { throw 'Legacy migration lost DB/input settings.' }
            if ((Test-Path $legacyApp) -or (Test-Path $legacyData) -or (Test-Path (Join-Path $Destination 'backups'))) {
                throw 'Successful migration left app/data/backups folders.'
            }
        }
    }
    Write-Host 'PASS: legacy app/data to flat installation, I/Q preservation and failed-migration restoration'



} finally {
    $root=[IO.Path]::GetFullPath((Join-Path $repo 'build')).TrimEnd('\')+'\'
    if ([IO.Path]::GetFullPath($case).StartsWith($root) -and (Split-Path $case -Leaf).StartsWith('offline-check-')) {
        Remove-Item -LiteralPath $case -Recurse -Force
    }
}
