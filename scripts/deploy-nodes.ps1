[CmdletBinding()]
param(
    [string[]]$TargetDirectories = @('C:\lnis-compose', 'C:\lnis-compose-리시버'),
    [string]$Jar = (Join-Path $PSScriptRoot '..\build\libs\lnis.jar'),
    [string]$Distribution = 'Ubuntu',
    [switch]$ValidateOnly
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Text.UTF8Encoding]::new($false)
Add-Type -AssemblyName System.IO.Compression.FileSystem

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

function Wait-Healthy($Target) {
    $deadline = (Get-Date).AddMinutes(3)
    do {
        $id = Invoke-Docker $Target.Linux @('compose', 'ps', '-q', 'node')
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
    # Config output can contain tokens. Capture it; do not print or save it.
    $config = (Invoke-Docker $linux @('compose', 'config', '--format', 'json')) -join "`n" | ConvertFrom-Json
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
    $id = Invoke-Docker $linux @('compose', 'ps', '-q', 'node')
    if (-not $id) { throw "Start the existing node before updating it: $path" }
    $previousImage = Invoke-Docker $linux @('inspect', '--format', '{{.Image}}', $id)
    $targets += [pscustomobject]@{ Path=$path; Linux=$linux; Image=$node.image; PreviousImage=$previousImage;
        Role=$node.environment.LNIS_NODE_ROLE; Backup=(Join-Path $path "backups\$timestamp") }
    Write-Host "Validated $($node.environment.LNIS_NODE_ROLE): $path"
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
    New-Item -ItemType Directory -Path $target.Backup | Out-Null
    foreach ($file in @('lnis.jar', 'Dockerfile', '.env')) {
        Copy-Item -LiteralPath (Join-Path $target.Path $file) -Destination $target.Backup
    }
    Get-ChildItem -LiteralPath $target.Path -Filter '*.yml' -File | Copy-Item -Destination $target.Backup
    $target.PreviousImage | Set-Content -LiteralPath (Join-Path $target.Backup 'previous-image.txt')
    try {
        Copy-Item -LiteralPath $source -Destination $stagedJar
        Assert-Jar $stagedJar $hash
        Move-Item -LiteralPath $stagedJar -Destination $deployedJar -Force
        $jarReplaced = $true
        # Also supports receiver deployments whose Compose file has no build section.
        Invoke-Docker $target.Linux @('build', '--tag', $target.Image, '.') | Out-Host
        $stopped = $true
        Invoke-Docker $target.Linux @('compose', 'stop', 'node') | Out-Host
        # H2 must be closed before copying its files.
        Copy-Item -LiteralPath (Join-Path $target.Path 'DB') -Destination $target.Backup -Recurse
        Invoke-Docker $target.Linux @('compose', 'up', '-d', '--no-build', '--no-deps', 'node') | Out-Host
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
        if ($stopped) {
            Invoke-Docker $target.Linux @('compose', 'up', '-d', '--no-build', '--no-deps', 'node') | Out-Host
            Wait-Healthy $target
        }
        throw $failure
    } finally {
        if (Test-Path -LiteralPath $stagedJar) { Remove-Item -LiteralPath $stagedJar }
    }
}
