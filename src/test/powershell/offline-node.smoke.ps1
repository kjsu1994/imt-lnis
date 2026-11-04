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
    $bad=$values.Clone();$bad.LNIS_NODE_MANAGEMENT_TOKEN=('bad'+[char]10+'value');Assert-Throws { Write-InstallEnvironment (Join-Path $repo 'deployment\node\.env.example') $file $bad }
    Assert-Throws { ConvertTo-NodeLinuxPath '\\server\share' }
    Assert-Throws { Assert-InstallLocation 'C:\' }
    Write-Host 'PASS: role/token/port validation, Korean env comments, installed paths and Compose arguments'

    $payload=Join-Path $app 'payload.txt';Set-Content $payload 'valid payload'
    @{image='lnis-node:test';imageId='sha256:test';files=@(@{path='payload.txt';sha256=(Get-FileHash $payload).Hash})} |
        ConvertTo-Json -Depth 4|Set-Content (Join-Path $app 'release.json') -Encoding UTF8
    Assert-NodeFiles $app | Out-Null
    Add-Content $payload 'changed';Assert-Throws { Assert-NodeFiles $app }
    @{image='test';imageId='sha256:test';files=@(@{path='..\outside.txt';sha256='test'})} |
        ConvertTo-Json -Depth 4|Set-Content (Join-Path $app 'release.json') -Encoding UTF8
    Assert-Throws { Assert-NodeFiles $app }
    Write-Host 'PASS: changed payload and outside-manifest paths rejected'

    $script:fakeProcess=$null;$script:started=0;$script:stopped=0
    $creation=[DateTime]'2026-01-01T00:00:00Z'
    function Get-CimInstance { param($ClassName,$Filter) return $script:fakeProcess }
    function Start-Process {
        param($FilePath,$ArgumentList,$WindowStyle,[switch]$PassThru,$RedirectStandardOutput,$RedirectStandardError)
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
    & ([ScriptBlock]::Create($source)) -NoBrowser
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
    Set-Content (Join-Path $bundleApp 'stop.ps1') ''
    Set-Content (Join-Path $bundleApp 'START.cmd') ''
    Set-Content (Join-Path $bundleApp 'STOP.cmd') ''
    function Invoke-InstallerAdmin { param($Arguments) $script:adminCalls+=($Arguments -join ' ') }
    function Assert-NodeFiles { param($Root) return $script:release }
    function Invoke-NodeWsl { param($Context,$Arguments) $script:installCalls+=($Arguments -join ' ') }
    function Get-NodeReady { param($Context) return 'http://127.0.0.1:18088' }
    function Wait-NodeReady { param($Context) return 'http://127.0.0.1:18088' }
    function Assert-NodeIdle { param($Context,$Url) $script:idleChecks++ }
    function wsl.exe { $global:LASTEXITCODE=0;return 'LNIS-Runtime' }
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

} finally {
    $root=[IO.Path]::GetFullPath((Join-Path $repo 'build')).TrimEnd('\')+'\'
    if ([IO.Path]::GetFullPath($case).StartsWith($root) -and (Split-Path $case -Leaf).StartsWith('offline-check-')) {
        Remove-Item -LiteralPath $case -Recurse -Force
    }
}
