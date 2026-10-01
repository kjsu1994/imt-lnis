$ErrorActionPreference = 'Stop'
$repo = Split-Path (Split-Path (Split-Path $PSScriptRoot))
$scriptPath = Join-Path $repo 'scripts\deploy-nodes.ps1'
$tokens = $null; $errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($scriptPath, [ref]$tokens, [ref]$errors)
if ($errors) { throw ($errors -join "`n") }
foreach ($function in $ast.FindAll({param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst]}, $false)) {
    Invoke-Expression $function.Extent.Text
}
foreach ($file in Get-ChildItem (Join-Path $repo 'deployment\node') -Filter '*.ps1') {
    [System.Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$tokens, [ref]$errors) | Out-Null
    if ($errors) { throw ($errors -join "`n") }
}
$caseRoot = Join-Path $repo ('build\operations-check-' + [Guid]::NewGuid())
$sourceRoot = Join-Path $caseRoot 'source'
$nodeRoot = Join-Path $caseRoot 'node'
$bridgeRoot = Join-Path $nodeRoot 'serial-bridge'
foreach ($folder in @('source\classes','source\lib','node\serial-bridge\classes','node\serial-bridge\lib')) {
    New-Item -ItemType Directory -Path (Join-Path $caseRoot $folder) -Force | Out-Null
}
Set-Content (Join-Path $sourceRoot 'classes\new.class') 'new-class'
Set-Content (Join-Path $sourceRoot 'lib\new.jar') 'new-library'
Set-Content (Join-Path $bridgeRoot 'classes\stale.class') 'old-class'
Set-Content (Join-Path $bridgeRoot 'lib\old.jar') 'old-library'
Set-Content (Join-Path $bridgeRoot 'bridge.properties') "bind=127.0.0.1`nport=18765`ntoken=test-only-secret-do-not-print"
$before = (Get-FileHash (Join-Path $bridgeRoot 'bridge.properties')).Hash
Install-Bridge $sourceRoot $bridgeRoot $nodeRoot
if ((Test-Path (Join-Path $bridgeRoot 'classes\stale.class')) -or (Test-Path (Join-Path $bridgeRoot 'lib\old.jar'))) { throw 'Obsolete bridge files retained' }
if (!(Test-Path (Join-Path $bridgeRoot 'classes\new.class')) -or !(Test-Path (Join-Path $bridgeRoot 'lib\new.jar'))) { throw 'New bridge missing' }
if ((Get-FileHash (Join-Path $bridgeRoot 'bridge.properties')).Hash -ne $before) { throw 'Configuration changed' }
$escaped = $false
try { Install-Bridge $sourceRoot (Join-Path $caseRoot 'outside') $nodeRoot } catch { $escaped = $true }
if (!$escaped) { throw 'Outside target accepted' }
$previousClasses = @(Get-ChildItem -LiteralPath $bridgeRoot -Directory | Where-Object Name -Like 'classes.previous-*')
$previousLibraries = @(Get-ChildItem -LiteralPath $bridgeRoot -Directory | Where-Object Name -Like 'lib.previous-*')
if ($previousClasses.Count -ne 1 -or $previousLibraries.Count -ne 1 -or
    !(Test-Path (Join-Path $previousClasses[0].FullName 'stale.class')) -or
    !(Test-Path (Join-Path $previousLibraries[0].FullName 'old.jar'))) {
    throw 'Previous generated files were deleted instead of preserved outside the classpath'
}
Write-Host 'PASS: generated bridge replacement, old-directory preservation, config preservation, path boundary'
Copy-Item (Join-Path $repo 'deployment\node\stop-serial-bridge.ps1') $nodeRoot
$pidPath = Join-Path $bridgeRoot 'bridge.pid'
$global:opsStopCalls = 0
$global:opsProcessCommand = 'RuntimeBroker.exe'
$global:opsBridgeBusy = $false
function Get-CimInstance { param($ClassName, $Filter) return [pscustomobject]@{CommandLine=$global:opsProcessCommand} }
function Invoke-RestMethod { param($Uri, $Method, $Headers, $ContentType, $Body, $TimeoutSec) return [pscustomobject]@{status='UP'; busy=$global:opsBridgeBusy} }
function Stop-Process { param($Id, $ErrorAction) $global:opsStopCalls++ }
function Wait-Process { param($Id, $Timeout, $ErrorAction) }
Set-Content $pidPath '12345'
& (Join-Path $nodeRoot 'stop-serial-bridge.ps1')
if ($global:opsStopCalls -ne 0 -or (Test-Path $pidPath)) { throw 'Stale PID affected another process' }
$global:opsProcessCommand = 'java server.gnss.WindowsSerialBridge ' + (Join-Path $bridgeRoot 'bridge.properties')
$global:opsBridgeBusy = $true
Set-Content $pidPath '12345'
$blocked = $false
try { & (Join-Path $nodeRoot 'stop-serial-bridge.ps1') } catch { $blocked = $_.Exception.Message.Contains('[GNSS_BUSY]') }
if (!$blocked -or $global:opsStopCalls -ne 0 -or !(Test-Path $pidPath)) { throw 'Active COM session was not protected' }
$global:opsBridgeBusy = $false
& (Join-Path $nodeRoot 'stop-serial-bridge.ps1')
if ($global:opsStopCalls -ne 1 -or (Test-Path $pidPath)) { throw 'Owned idle bridge did not stop' }
Write-Host 'PASS: stale PID isolation, active session protection, owned idle stop'

$global:opsActiveCapture = $false
$global:opsActiveCalculation = $false
$global:opsTrialPages = 0
function Invoke-RestMethod {
    param($Uri, $Method, $Headers, $ContentType, $Body, $TimeoutSec)
    if ($Uri.EndsWith('/node')) { return [pscustomobject]@{state='READY'} }
    if ($Uri.EndsWith('/gnss')) { return [pscustomobject]@{state='CONNECTED';capturing=$global:opsActiveCapture} }
    $global:opsTrialPages++
    if ($Uri.EndsWith('page=0')) { return ,@(1..50 | ForEach-Object { [pscustomobject]@{state='WAITING_DTN'} }) }
    return ,@([pscustomobject]@{state=$(if ($global:opsActiveCalculation) {'CALCULATING'} else {'COMPLETED'})})
}
$target = [pscustomobject]@{Url='http://example.invalid'; Path=$nodeRoot; GnssConnected=$false}
Assert-NodeIdle $target
if (!$target.GnssConnected -or $global:opsTrialPages -ne 2) { throw 'Connected idle / waiting pagination failed' }
$global:opsActiveCapture = $true
$blocked = $false
try { Assert-NodeIdle $target } catch { $blocked = $true }
if (!$blocked) { throw 'Capture was not blocked' }
$global:opsActiveCapture = $false
$global:opsActiveCalculation = $true
$blocked = $false
try { Assert-NodeIdle $target } catch { $blocked = $true }
if (!$blocked) { throw 'Older active trial was not blocked' }
Write-Host 'PASS: idle persistent connection, queued trials, active capture/calculation detection'

Set-Content (Join-Path $nodeRoot 'docker-compose.yml') 'services: {}'
Set-Content (Join-Path $nodeRoot 'docker-compose.windows-serial.yml') 'services: {}'
$labels = [pscustomobject]@{
    'com.docker.compose.project' = 'lnis-custom-role'
    'com.docker.compose.project.config_files' = '/mnt/test/node/docker-compose.yml,/mnt/test/node/docker-compose.windows-serial.yml'
}
$composeArguments = Get-ComposeCommand $labels '/mnt/test/node' $nodeRoot
if (($composeArguments -join '|') -ne 'compose|--project-name|lnis-custom-role|-f|/mnt/test/node/docker-compose.yml|-f|/mnt/test/node/docker-compose.windows-serial.yml') {
    throw 'Running project name or overlay was lost'
}
$labels.'com.docker.compose.project.config_files' = '/mnt/test/other/compose.yml'
$blocked = $false
try { Get-ComposeCommand $labels '/mnt/test/node' $nodeRoot | Out-Null } catch { $blocked = $true }
if (!$blocked) { throw 'Outside Compose path was accepted' }
Write-Host 'PASS: actual Compose project/overlay preservation and path validation'

function Invoke-RestMethod { param($Uri, $Method, $Headers, $ContentType, $Body, $TimeoutSec) throw 'simulated bridge connection failure' }
$global:opsProcessCommand = 'java unrelated.application.Main'
Assert-BridgeIdle $nodeRoot
$global:opsProcessCommand = 'java server.gnss.WindowsSerialBridge ' + (Join-Path $bridgeRoot 'bridge.properties')
$blocked = $false
try { Assert-BridgeIdle $nodeRoot } catch { $blocked = $true }
if (!$blocked) { throw 'Unresponsive owned bridge was accepted as stopped' }
Write-Host 'PASS: stopped bridge permitted, live unresponsive owned bridge protected'

# Parameter defaults must not evaluate Join-Path with missing secured-drive metadata.
$sourceText = Get-Content -LiteralPath $scriptPath -Raw
$setup = $sourceText.Substring(0, $sourceText.IndexOf('function Assert-Jar'))
$sourceResult = & ([ScriptBlock]::Create($setup + "`n[pscustomobject]@{Jar=`$Jar; Bridge=`$BridgeDistribution; Launchers=`$sourceScripts}")) -SourceDirectory $repo
if ($sourceResult.Jar -ne (Join-Path $repo 'build\libs\lnis.jar') -or
    $sourceResult.Bridge -ne (Join-Path $repo 'build\windows-serial-bridge') -or
    $sourceResult.Launchers -ne (Join-Path $repo 'deployment\node')) {
    throw 'Explicit source directory did not resolve deployment inputs'
}
Write-Host 'PASS: explicit source root works without script-path metadata'

# Native UTF-8 stdout must survive a redirected parent process, including Korean Compose paths.
$expectedDirectory = '/mnt/c/lnis-compose-리시버'
$fixtureJson = @{directory=$expectedDirectory} | ConvertTo-Json -Compress
$childCommand = '[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false); [Console]::Write(' + "'" + $fixtureJson.Replace("'", "''") + "')"
$encodedCommand = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($childCommand))
$decoded = ((& powershell.exe -NoProfile -EncodedCommand $encodedCommand) -join "`n") | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or $decoded.directory -ne $expectedDirectory) {
    throw 'Native UTF-8 JSON or Korean deployment path was corrupted'
}
Write-Host 'PASS: native UTF-8 JSON and Korean deployment paths with redirected stdout'

# Read configuration without opening COM ports, starting JVMs, or touching deployed settings.
$bridgeScript = Join-Path $repo 'deployment\node\start-serial-bridge.ps1'
$bridgeAst = [System.Management.Automation.Language.Parser]::ParseFile($bridgeScript, [ref]$tokens, [ref]$errors)
foreach ($function in $bridgeAst.FindAll({param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst]}, $false)) {
    Invoke-Expression $function.Extent.Text
}
$environmentFile = Join-Path $nodeRoot '.env'
$absentConfig = Join-Path $nodeRoot 'not-created.properties'
$configFile = Join-Path $bridgeRoot 'bridge.properties'
Set-Content $environmentFile 'LNIS_NODE_ROLE=sender'
if ((Get-BridgeSettings $environmentFile $absentConfig).Port -ne 18765) { throw 'New sender default changed' }
Set-Content $environmentFile 'LNIS_NODE_ROLE=receiver'
if ((Get-BridgeSettings $environmentFile $absentConfig).Port -ne 18766) { throw 'New receiver default changed' }
Set-Content $configFile "bind=127.0.0.1`nport=24567`ntoken=test-only-secret-do-not-print"
Set-Content $environmentFile "LNIS_NODE_ROLE=receiver`nLNIS_SERIAL_BRIDGE_PORT="
if ((Get-BridgeSettings $environmentFile $configFile).Port -ne 24567) { throw 'Existing custom port was overwritten' }
Set-Content $environmentFile @(' LNIS_SERIAL_BRIDGE_PORT = "24568" # custom', 'LNIS_SERIAL_BRIDGE_BIND=127.0.0.1', 'LNIS_SERIAL_BRIDGE_HOST=host.docker.internal')
$settings = Get-BridgeSettings $environmentFile $configFile
if ($settings.Port -ne 24568 -or $settings.Bind -ne '127.0.0.1' -or $settings.Host -ne 'host.docker.internal') {
    throw 'Explicit settings were not applied'
}
foreach ($port in @('1', '65535')) {
    Set-Content $environmentFile ('LNIS_SERIAL_BRIDGE_PORT=' + $port)
    if ((Get-BridgeSettings $environmentFile $configFile).Port -ne [int]$port) { throw 'Port boundary rejected' }
}
foreach ($setting in @('PORT=0', 'PORT=65536', 'PORT=abc', 'PORT=999999999999', 'BIND=0.0.0.0', 'BIND=localhost', 'HOST=http://localhost:1234')) {
    Set-Content $environmentFile ('LNIS_SERIAL_BRIDGE_' + $setting)
    $blocked = $false
    try { Get-BridgeSettings $environmentFile $configFile | Out-Null } catch { $blocked = $_.Exception.Message.Contains('[GNSS_CONFIG]') }
    if (!$blocked) { throw ('Invalid bridge setting accepted: ' + $setting) }
}
Write-Host 'PASS: bridge defaults, existing port preservation, single env override, host/bind and port validation'

Copy-Item $bridgeScript $nodeRoot
$mainClass = Join-Path $bridgeRoot 'classes\server\gnss'
New-Item -ItemType Directory -Path $mainClass -Force | Out-Null
Set-Content (Join-Path $mainClass 'WindowsSerialBridge.class') 'isolated-fixture'
$hashLines = @(Get-ChildItem -LiteralPath (Join-Path $bridgeRoot 'classes'), (Join-Path $bridgeRoot 'lib') -File -Recurse |
    Where-Object { $_.Extension -in @('.class', '.jar') } | Sort-Object FullName | ForEach-Object {
        $_.FullName.Substring($bridgeRoot.Length + 1).Replace('\', '/') + '=' + (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
    })
$sha = [Security.Cryptography.SHA256]::Create()
try { $global:opsBridgeVersion = ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($hashLines -join "`n")))).Replace('-', '').ToLowerInvariant() }
finally { $sha.Dispose() }
$global:opsHealthUri = $null
function Get-NetIPAddress { param($AddressFamily) return [pscustomobject]@{IPAddress='127.0.0.1'} }
function Start-Process { throw 'Isolated startup test must not launch a real process' }
function Invoke-RestMethod {
    param($Uri, $Method, $Headers, $ContentType, $Body, $TimeoutSec)
    $global:opsHealthUri = $Uri
    return [pscustomobject]@{status='UP'; busy=$false; version=$global:opsBridgeVersion}
}
Set-Content $environmentFile "LNIS_NODE_ROLE=receiver`nLNIS_SERIAL_BRIDGE_PORT="
$beforeConfig = (Get-FileHash $configFile).Hash
$beforeStops = $global:opsStopCalls
& (Join-Path $nodeRoot 'start-serial-bridge.ps1') -DockerDesktop
if ($global:opsHealthUri -ne 'http://127.0.0.1:24567/health' -or
    $global:opsStopCalls -ne $beforeStops -or (Get-FileHash $configFile).Hash -ne $beforeConfig) {
    throw 'Healthy bridge was restarted or its existing port/configuration changed'
}
$generatedEnvironment = Get-Content -LiteralPath (Join-Path $nodeRoot '.serial-bridge.env')
if ($generatedEnvironment[0] -ne 'LNIS_SERIAL_BRIDGE_URL=http://host.docker.internal:24567') {
    throw 'Container URL did not use the existing custom bridge port'
}
Write-Host 'PASS: already-running custom port reused and container URL generated without restarting or changing credentials'
