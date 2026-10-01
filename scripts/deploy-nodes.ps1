[CmdletBinding()]
param(
    [string[]]$TargetDirectories = @('C:\lnis-compose', 'C:\lnis-compose-리시버'),
    [string]$Jar,
    [string]$Distribution = 'Ubuntu',
    [string]$BridgeDistribution,
    [string]$SourceDirectory,
    [switch]$ValidateOnly
)

$ErrorActionPreference = 'Stop'
# WSL emits UTF-8 even when PowerShell stdout is redirected to a deployment log.
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$OutputEncoding = [Text.UTF8Encoding]::new($false)
Add-Type -AssemblyName System.IO.Compression.FileSystem

# Some secured drives do not expose script metadata to PowerShell. Keep an explicit source-root option.
if ([string]::IsNullOrWhiteSpace($SourceDirectory)) {
    if ([string]::IsNullOrWhiteSpace($PSScriptRoot)) {
        throw 'Cannot determine the source directory. Pass -SourceDirectory with the repository root.'
    }
    $SourceDirectory = Join-Path $PSScriptRoot '..'
}
$SourceDirectory = [IO.Path]::GetFullPath($SourceDirectory)
if ([string]::IsNullOrWhiteSpace($Jar)) { $Jar = Join-Path $SourceDirectory 'build\libs\lnis.jar' }
if ([string]::IsNullOrWhiteSpace($BridgeDistribution)) { $BridgeDistribution = Join-Path $SourceDirectory 'build\windows-serial-bridge' }
$sourceScripts = Join-Path $SourceDirectory 'deployment\node'

function Assert-Jar([string]$Path, [string]$ExpectedHash) {
    if ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -ne $ExpectedHash) {
        throw "JAR SHA-256 mismatch: $Path"
    }
    $zip = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        foreach ($entry in @('META-INF/MANIFEST.MF', 'BOOT-INF/classes/application.yml',
                'BOOT-INF/classes/server/LnisApplication.class',
                'BOOT-INF/classes/static/dtn-sender.html')) {
            if (-not $zip.GetEntry($entry)) { throw "Missing JAR entry: $entry" }
        }
    } finally { $zip.Dispose() }
}

function Invoke-Docker([string]$Directory, [string[]]$Arguments) {
    # Use a local working directory even when the source is on a secured drive.
    Push-Location $env:SystemRoot
    try {
        $output = & wsl.exe -d $Distribution --cd $Directory -- docker @Arguments
        if ($LASTEXITCODE -ne 0) { throw "Docker command failed: $($Arguments[0])" }
        return $output
    } finally { Pop-Location }
}

function Get-ComposeCommand($Labels, [string]$Linux, [string]$Path) {
    $files = $labels.'com.docker.compose.project.config_files'
    $project = $labels.'com.docker.compose.project'
    if (!$files -or !$project) { throw "Missing Compose project metadata: $path" }
    $compose = @('compose', '--project-name', $project)
    foreach ($file in $files.Split(',')) {
        if (!$file.StartsWith($linux + '/') -or $file.Split('/') -contains '..') {
            throw "Compose file must remain inside the deployment directory: $file"
        }
        $relative = $file.Substring($linux.Length + 1).Replace('/', '\')
        if (!(Test-Path -LiteralPath (Join-Path $path $relative) -PathType Leaf)) { throw "Missing Compose file: $file" }
        $compose += @('-f', $file)
    }
    return ,$compose
}

function Assert-BridgeBuild([string]$JarPath, [string]$BridgePath) {
    $zip = [IO.Compression.ZipFile]::OpenRead($JarPath)
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        foreach ($folder in @('classes', 'lib')) {
            $base = Join-Path $BridgePath $folder
            foreach ($file in Get-ChildItem -LiteralPath $base -File -Recurse) {
                if ($file.Extension -notin @('.class', '.jar')) { continue }
                $relative = $file.FullName.Substring($base.Length + 1).Replace('\', '/')
                $entry = $zip.GetEntry("BOOT-INF/$folder/$relative")
                if (!$entry) { throw "Bridge artifact is absent from the server JAR: $relative" }
                $stream = $entry.Open()
                try { $entryHash = ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '') }
                finally { $stream.Dispose() }
                if ($entryHash -ne (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash) {
                    throw "Bridge and server JAR differ. Build bootJar windowsSerialBridgeDist together: $relative"
                }
            }
        }
    } finally { $sha.Dispose(); $zip.Dispose() }
}

function Assert-NodeIdle($Target) {
    $status = Invoke-RestMethod -Uri ($Target.Url + '/lnis/api/v1/node') -TimeoutSec 5
    $gnss = Invoke-RestMethod -Uri ($Target.Url + '/lnis/api/v1/node/gnss') -TimeoutSec 5
    if ($status.state -eq 'BUSY' -or $gnss.capturing) {
        throw "GNSS capture is active. Finish or stop it before deploying: $($Target.Path)"
    }
    $Target.GnssConnected = $gnss.state -eq 'CONNECTED'
    $page = 0
    do {
        $response = Invoke-RestMethod -Uri ($Target.Url + '/lnis/api/v1/dtn/tests?page=' + $page) -TimeoutSec 10
        $trials = @($response)
        if ($trials | Where-Object { $_.state -in @('PREPARING', 'CALCULATING') -or $_.sendStatus -eq 'REQUESTING' }) {
            throw "A trial is preparing, sending or calculating. Wait before deploying: $($Target.Path)"
        }
        $page++
    } while ($trials.Count -eq 50)
}

function Wait-BridgeIdle([string]$Directory) {
    $deadline = (Get-Date).AddSeconds(35)
    do {
        try { Assert-BridgeIdle $Directory; return }
        catch {
            if ((Get-Date) -ge $deadline) { throw }
            Start-Sleep -Milliseconds 500
        }
    } while ($true)
}

function Assert-BridgeIdle([string]$Directory, [bool]$AllowConnected = $false) {
    $config = Join-Path $Directory 'serial-bridge\bridge.properties'
    if (!(Test-Path -LiteralPath $config)) { throw "Missing bridge configuration: $Directory" }
    $props = @{}
    foreach ($line in Get-Content -LiteralPath $config) {
        if ($line.Contains('=')) { $pair = $line.Split('=', 2); $props[$pair[0]] = $pair[1] }
    }
    try {
        $health = Invoke-RestMethod -Uri ("http://" + $props.bind + ":" + $props.port + "/health") -Method Post -Headers @{Authorization=('Bearer ' + $props.token)} -ContentType application/json -Body '{}' -TimeoutSec 3
    } catch {
        $healthFailure = $_
        $processes = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'")
        $running = @($processes | Where-Object {
            !$_.CommandLine -or ($_.CommandLine.Contains('server.gnss.WindowsSerialBridge') -and
                $_.CommandLine.IndexOf($config, [StringComparison]::OrdinalIgnoreCase) -ge 0)
        })
        if ($running.Count) { throw $healthFailure }
        # A stopped bridge has no active COM lease. Deployment will start the matching build.
        return
    }
    if ($health.status -ne 'UP' -or ($health.busy -and !$AllowConnected)) {
        throw "Disconnect GNSS before updating its bridge: $Directory"
    }
}

function Install-Bridge([string]$Source, [string]$Destination, [string]$TargetRoot) {
    $prefix = [IO.Path]::GetFullPath($TargetRoot).TrimEnd('\') + '\'
    foreach ($folder in @($TargetRoot, $Destination)) {
        if ((Test-Path -LiteralPath $folder) -and ((Get-Item -LiteralPath $folder).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "Refusing a linked deployment directory: $folder"
        }
    }
    foreach ($name in @('classes', 'lib')) {
        $targetFolder = [IO.Path]::GetFullPath((Join-Path $Destination $name))
        if (!$targetFolder.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Bridge replacement escaped the deployment directory: $targetFolder"
        }
        if (Test-Path -LiteralPath $targetFolder) {
            $entries = @(Get-Item -LiteralPath $targetFolder) + @(Get-ChildItem -LiteralPath $targetFolder -Recurse -Force)
            if ($entries | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) {
                throw "Refusing to replace a bridge directory containing links: $targetFolder"
            }
            # Preserve old generated files outside the active classpath. Do not recursively delete them.
            $previousFolder = [IO.Path]::GetFullPath((Join-Path $Destination ($name + '.previous-' + [Guid]::NewGuid().ToString('N'))))
            if (!$previousFolder.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase) -or
                (Test-Path -LiteralPath $previousFolder)) {
                throw "Unsafe bridge preservation path: $previousFolder"
            }
            Write-Host "Preserving previous bridge $name directory."
            Move-Item -LiteralPath $targetFolder -Destination $previousFolder
        }
        Write-Host "Installing current bridge $name directory."
        Copy-Item -LiteralPath (Join-Path $Source $name) -Destination $targetFolder -Recurse
    }
}

function Wait-Healthy($Target) {
    $deadline = (Get-Date).AddMinutes(3)
    do {
        $id = Invoke-Docker $Target.Linux ($Target.Compose + @('ps', '-q', 'node'))
        if ($id) {
            $health = Invoke-Docker $Target.Linux @('inspect', '--format', '{{.State.Health.Status}}', $id)
            if ($health -eq 'healthy') { return }
        }
        Start-Sleep -Seconds 3
    } while ((Get-Date) -lt $deadline)
    throw "Readiness timed out: $($Target.Path)"
}

$source = (Resolve-Path -LiteralPath $Jar).Path
$hash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
Assert-Jar $source $hash
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$targets = @()

# Validate every target before making any changes. Never replace its Compose or .env.
foreach ($directory in $TargetDirectories) {
    $path = (Resolve-Path -LiteralPath $directory).Path.TrimEnd('\')
    if ($path -notmatch '^[A-Za-z]:\\') { throw 'A local Windows deployment directory is required.' }
    if ($targets.Path -contains $path) { throw "Duplicate deployment directory: $path" }
    $linux = '/mnt/' + $path.Substring(0, 1).ToLowerInvariant() + '/' + $path.Substring(3).Replace('\', '/')
    foreach ($file in @('docker-compose.yml', '.env', 'Dockerfile', 'lnis.jar', 'DB')) {
        if (-not (Test-Path -LiteralPath (Join-Path $path $file))) { throw "Missing deployment file: $path\$file" }
    }
    if ($source -eq (Join-Path $path 'lnis.jar')) { throw 'Build JAR and deployed JAR must be separate files.' }
    # Read the running project's complete file list; an explicit Windows/USB overlay must not disappear.
    $ids = @(Invoke-Docker $linux @('ps', '--filter', "label=com.docker.compose.project.working_dir=$linux",
        '--filter', 'label=com.docker.compose.service=node', '--format', '{{.ID}}'))
    if ($ids.Count -ne 1) { throw "Expected one running LNIS node: $path" }
    $id = $ids[0]
    $labels = (Invoke-Docker $linux @('inspect', '--format', '{{json .Config.Labels}}', $id)) -join "`n" | ConvertFrom-Json
    $compose = Get-ComposeCommand $labels $linux $path
    # Config output can contain tokens. Capture it; do not print or save it.
    $config = (Invoke-Docker $linux ($compose + @('config', '--format', 'json'))) -join "`n" | ConvertFrom-Json
    $node = $config.services.node
    if (-not $node -or $node.environment.LNIS_NODE_ROLE -notin @('sender', 'receiver')) {
        throw "Not an independent LNIS node: $path"
    }
    if (-not $node.image -or -not $node.healthcheck) { throw "Node image/healthcheck is required: $path" }
    if ($targets.Image -contains $node.image) { throw 'Targets must use different image tags.' }
    $db = @($node.volumes | Where-Object { $_.target -eq '/app/data' })
    if ($db.Count -ne 1 -or $db[0].type -ne 'bind' -or $db[0].source -ne "$linux/DB") {
        throw "Expected local DB bind mount at $linux/DB"
    }
    if ($node.build -and ($node.build.context -ne $linux -or $node.build.dockerfile -ne 'Dockerfile')) {
        throw "Expected the deployment directory's Dockerfile: $path"
    }
    $previousImage = Invoke-Docker $linux @('inspect', '--format', '{{.Image}}', $id)
    $targets += [pscustomobject]@{ Path=$path; Linux=$linux; Image=$node.image; PreviousImage=$previousImage;
        Compose=$compose; Bridge=[bool]$node.environment.LNIS_SERIAL_BRIDGE_URL;
        Url=([string]$node.environment.LNIS_NODE_BASE_URL).TrimEnd('/'); GnssConnected=$false;
        Role=$node.environment.LNIS_NODE_ROLE; Backup=(Join-Path $path "backups\$timestamp") }
    Write-Host "Validated $($node.environment.LNIS_NODE_ROLE): $path"
}
foreach ($target in $targets) { Assert-NodeIdle $target }
$bridgeSource = $null
$bridgeScripts = @('start-serial-bridge.ps1', 'stop-serial-bridge.ps1', 'start.ps1', 'stop.ps1')
if ($targets | Where-Object Bridge) {
    foreach ($script in $bridgeScripts) {
        if (!(Test-Path -LiteralPath (Join-Path $sourceScripts $script) -PathType Leaf)) {
            throw "Missing bridge launcher source: $script"
        }
    }
    $bridgeSource = (Resolve-Path -LiteralPath $BridgeDistribution).Path
    foreach ($required in @('classes\server\gnss\WindowsSerialBridge.class', 'lib')) {
        if (!(Test-Path -LiteralPath (Join-Path $bridgeSource $required))) { throw 'Build windowsSerialBridgeDist before deploying.' }
    }
    Assert-BridgeBuild $source $bridgeSource
    foreach ($target in $targets | Where-Object Bridge) {
        if ($bridgeSource.StartsWith($target.Path + '\', [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Build bridge and deployed bridge must be separate directories.'
        }
        Assert-BridgeIdle $target.Path $target.GnssConnected
    }
}
if ($ValidateOnly) {
    Write-Host "Validation complete. JAR SHA-256: $hash"
    return
}

foreach ($target in $targets) {
    $deployedJar = Join-Path $target.Path 'lnis.jar'
    $stagedJar = Join-Path $target.Path "lnis.jar.$timestamp.tmp"
    $stopped = $false
    $jarReplaced = $false
    $bridgeReplaced = $false
    New-Item -ItemType Directory -Path $target.Backup | Out-Null
    foreach ($file in @('lnis.jar', 'Dockerfile', '.env')) {
        Copy-Item -LiteralPath (Join-Path $target.Path $file) -Destination $target.Backup
    }
    Get-ChildItem -LiteralPath $target.Path -Filter '*.yml' -File | Copy-Item -Destination $target.Backup
    if ($target.Bridge) {
        Copy-Item -LiteralPath (Join-Path $target.Path 'serial-bridge') -Destination $target.Backup -Recurse
        foreach ($script in $bridgeScripts) {
            $installed = Join-Path $target.Path $script
            if (Test-Path -LiteralPath $installed) { Copy-Item -LiteralPath $installed -Destination $target.Backup }
        }
    }
    $target.PreviousImage | Set-Content -LiteralPath (Join-Path $target.Backup 'previous-image.txt')
    try {
        Copy-Item -LiteralPath $source -Destination $stagedJar
        Assert-Jar $stagedJar $hash
        Move-Item -LiteralPath $stagedJar -Destination $deployedJar -Force
        $jarReplaced = $true
        # Also supports receiver deployments whose Compose file has no build section.
        Invoke-Docker $target.Linux @('build', '--tag', $target.Image, '.') | Out-Host
        Assert-NodeIdle $target
        if ($target.Bridge) { Assert-BridgeIdle $target.Path $target.GnssConnected }
        $stopped = $true
        Invoke-Docker $target.Linux ($target.Compose + @('stop', 'node')) | Out-Host
        # H2 must be closed before copying its files.
        Copy-Item -LiteralPath (Join-Path $target.Path 'DB') -Destination $target.Backup -Recurse
        if ($target.Bridge) {
            $bridgeReplaced = $true
            foreach ($script in $bridgeScripts) {
                Copy-Item -LiteralPath (Join-Path $sourceScripts $script) -Destination $target.Path -Force
            }
            Wait-BridgeIdle $target.Path
            & (Join-Path $target.Path 'stop-serial-bridge.ps1')
            Install-Bridge $bridgeSource (Join-Path $target.Path 'serial-bridge') $target.Path
            & (Join-Path $target.Path 'start-serial-bridge.ps1')
        }
        Invoke-Docker $target.Linux ($target.Compose + @('up', '-d', '--no-build', '--no-deps', 'node')) | Out-Host
        Wait-Healthy $target
        $stopped = $false
        Write-Host "Updated $($target.Role). Backup: $($target.Backup)"
    } catch {
        $failure = $_
        if ($jarReplaced) {
            $previousJar = Join-Path $target.Backup 'lnis.jar'
            Copy-Item -LiteralPath $previousJar -Destination $stagedJar -Force
            Assert-Jar $stagedJar (Get-FileHash -LiteralPath $previousJar -Algorithm SHA256).Hash
            Move-Item -LiteralPath $stagedJar -Destination $deployedJar -Force
            Invoke-Docker $target.Linux @('tag', $target.PreviousImage, $target.Image) | Out-Host
        }
        if ($bridgeReplaced) {
            & (Join-Path $target.Path 'stop-serial-bridge.ps1')
            Install-Bridge (Join-Path $target.Backup 'serial-bridge') (Join-Path $target.Path 'serial-bridge') $target.Path
            foreach ($script in $bridgeScripts) {
                $previousScript = Join-Path $target.Backup $script
                if (Test-Path -LiteralPath $previousScript) { Copy-Item -LiteralPath $previousScript -Destination $target.Path -Force }
            }
            & (Join-Path $target.Path 'start-serial-bridge.ps1')
        }
        if ($stopped) {
            Invoke-Docker $target.Linux ($target.Compose + @('up', '-d', '--no-build', '--no-deps', 'node')) | Out-Host
            Wait-Healthy $target
        }
        throw $failure
    } finally {
        if (Test-Path -LiteralPath $stagedJar) { Remove-Item -LiteralPath $stagedJar }
    }
}
